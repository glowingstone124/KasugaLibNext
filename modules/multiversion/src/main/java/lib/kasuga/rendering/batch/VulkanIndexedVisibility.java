package lib.kasuga.rendering.batch;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import lib.kasuga.core.rendering.mixin.FrontendGpuDeviceAccess;
import lib.kasuga.core.rendering.mixin.VulkanDeviceFeaturesAccess;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;

/** RenderPearl 26.3 interop for indexed batch visibility. */
public final class VulkanIndexedVisibility {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SHADER = "/assets/kasuga_lib/shaders/compute/indexed_visibility.comp";
    private static final int GROUP_SIZE = 64;
    private static final int COUNTED_DRAWS_PER_GROUP = 256; // 64 lanes × 4 consecutive candidates in the shader.
    private static final int CONSTANT_BYTES = (6 * 4 + 2) * Integer.BYTES;
    private static final FeatureSet INDIRECT_COUNT_FEATURE = new FeatureSet("Kasuga drawIndirectCount", Set.of(),
            Set.of(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "drawIndirectCount")));
    private static final int BYTES_PER_CANDIDATE = IndexedDrawBatch.COMMAND_BYTES + 4 * Float.BYTES;
    private static @Nullable VulkanDevice cachedDevice;
    private static @Nullable Pipeline cachedPipeline;

    private VulkanIndexedVisibility() {}

    public static FeatureSet indirectCountFeature() {
        return INDIRECT_COUNT_FEATURE;
    }

    public static boolean isIndirectCountDisabled() {
        return Boolean.getBoolean("kasuga.disableVulkanDrawIndirectCount");
    }

    static @Nullable Prepared prepare(CommandEncoder encoder, List<IndexedDrawBatch.Candidate> candidates,
                                      List<? extends Vector4fc> planes, int maxDraws) {
        if (Boolean.getBoolean("kasuga.disableVulkanCompute")) return null;
        GpuDevice active = RenderSystem.getDevice();
        if (encoder != active.createCommandEncoder() || !(active instanceof FrontendGpuDeviceAccess access)
                || !(access.kasuga$backend() instanceof VulkanDevice device)) return null;
        if (cachedDevice != device) {
            cachedDevice = device;
            cachedPipeline = createPipelineIfSupported(device);
        }
        Pipeline pipeline = cachedPipeline;
        if (pipeline == null || candidates.size() > pipeline.maxCandidates()) return null;

        VulkanCommandEncoder commands = device.createCommandEncoder();
        int count = candidates.size();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int chunkSize = Math.min(maxDraws, COUNTED_DRAWS_PER_GROUP);
            int groups = Math.ceilDiv(count, chunkSize);
            BatchBuffer buffer = null;
            if (groups <= pipeline.maxDispatchGroups() && supportsIndirectCount(device)) {
                long bytes = (long) count * (BYTES_PER_CANDIDATE + IndexedDrawBatch.COMMAND_BYTES)
                        + (long) groups * Integer.BYTES;
                if (bytes <= pipeline.maxBufferBytes()) {
                    buffer = allocateBatch(device, stack, (int) bytes, candidates, chunkSize);
                }
            }
            if (buffer == null) {
                buffer = allocateBatch(device, stack, count * BYTES_PER_CANDIDATE, candidates, 0);
                if (buffer == null) return null;
            }
            // Keep the buffer valid while recording the draw. The host drains it after the submission.
            buffer.retireAfterSubmit();
            VkCommandBuffer command = commands.allocateAndBeginTransientCommandBuffer();
            record(command, stack, pipeline, buffer, count, planes);
            VulkanUtils.crashIfFailure(device, vkEndCommandBuffer(command), "Ending indexed visibility command buffer");
            commands.execute(command);
            GpuBufferSlice slice = buffer.slice(0, (long) count * IndexedDrawBatch.COMMAND_BYTES);
            boolean counted = buffer.chunkSize != 0;
            return new Prepared(slice, counted ? buffer.chunkSize : maxDraws, counted);
        }
    }

    private static boolean supportsIndirectCount(VulkanDevice device) {
        return !isIndirectCountDisabled()
                && ((VulkanDeviceFeaturesAccess) (Object) device).kasuga$enabledFeatures().contains(INDIRECT_COUNT_FEATURE)
                && device.vkDevice().getCapabilities().vkCmdDrawIndexedIndirectCount != 0;
    }

    /** Called by the render-pass hook only after the host validates and binds the draw state. */
    public static boolean tryDrawCounted(VkCommandBuffer command, GpuBufferSlice commands, int maxDrawCount, int stride) {
        if (!(commands.buffer() instanceof BatchBuffer buffer) || buffer.chunkSize == 0) return false;
        long chunk = commands.offset() / ((long) buffer.chunkSize * stride);
        long countOffset = buffer.countsOffset + chunk * Integer.BYTES;
        vkCmdDrawIndexedIndirectCount(command, buffer.vkBuffer(), commands.offset(), buffer.vkBuffer(), countOffset,
                maxDrawCount, stride);
        return true;
    }

    record Prepared(GpuBufferSlice commands, int drawsPerSubmission, boolean counted) {}

    /** Called by the host device close hook before it drains its graphics queue. */
    public static void release(VulkanDevice device) {
        if (cachedDevice != device) return;
        Pipeline pipeline = cachedPipeline;
        cachedDevice = null;
        cachedPipeline = null;
        if (pipeline != null) device.createCommandEncoder().queueForDestroy(() -> pipeline.destroy(device.vkDevice()));
    }

    private static @Nullable Pipeline createPipelineIfSupported(VulkanDevice device) {
        VkDevice vk = device.vkDevice();
        if (!vk.getCapabilities().VK_KHR_push_descriptor || vk.getCapabilities().vkCmdDispatch == 0) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDevice physical = vk.getPhysicalDevice();
            IntBuffer familyCount = stack.callocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, familyCount, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(familyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, familyCount, families);
            int graphicsFamily = device.graphicsQueue().queueFamilyIndex();
            if (graphicsFamily >= familyCount.get(0) || (families.get(graphicsFamily).queueFlags() & VK_QUEUE_COMPUTE_BIT) == 0) {
                return null;
            }
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            VkPhysicalDeviceLimits limits = properties.limits();
            if (limits.maxComputeWorkGroupInvocations() < GROUP_SIZE || limits.maxComputeWorkGroupSize(0) < GROUP_SIZE
                    || limits.maxPushConstantsSize() < CONSTANT_BYTES) return null;
            int maxGroups = (int) Math.min(Integer.toUnsignedLong(limits.maxComputeWorkGroupCount(0)), Integer.MAX_VALUE);
            long maxRange = Integer.toUnsignedLong(limits.maxStorageBufferRange());
            int maxBufferBytes = (int) Math.min(maxRange, Integer.MAX_VALUE);
            int capacity = (int) Math.min((long) maxGroups * GROUP_SIZE, maxBufferBytes / BYTES_PER_CANDIDATE);
            if (capacity < 2) return null;
            return createPipeline(device, stack, capacity, maxBufferBytes, maxGroups);
        } catch (IOException | IllegalStateException error) {
            LOGGER.warn("Vulkan indexed visibility could not initialize. Using CPU visibility.", error);
            return null;
        }
    }

    private static Pipeline createPipeline(VulkanDevice device, MemoryStack stack, int capacity, int maxBufferBytes,
                                           int maxDispatchGroups) throws IOException {
        VkDevice vk = device.vkDevice();
        long shader = createShader(device, stack);
        long descriptorLayout = 0;
        long pipelineLayout = 0;
        try {
            LongBuffer out = stack.callocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo descriptors = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(binding);
            VulkanUtils.crashIfFailure(device, vkCreateDescriptorSetLayout(vk, descriptors, null, out), "Creating indexed visibility descriptors");
            descriptorLayout = out.get(0);

            VkPushConstantRange.Buffer constants = VkPushConstantRange.calloc(1, stack);
            constants.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(CONSTANT_BYTES);
            VkPipelineLayoutCreateInfo layout = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(constants);
            VulkanUtils.crashIfFailure(device, vkCreatePipelineLayout(vk, layout, null, out), "Creating indexed visibility layout");
            pipelineLayout = out.get(0);

            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.sType$Default().layout(pipelineLayout);
            info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            out.put(0, 0L);
            int result = vkCreateComputePipelines(vk, 0, info, null, out);
            long pipeline = out.get(0);
            if (result != VK_SUCCESS) {
                if (pipeline != 0) vkDestroyPipeline(vk, pipeline, null);
                VulkanUtils.crashIfFailure(device, result, "Creating indexed visibility pipeline");
                throw new IllegalStateException("Unexpected compute pipeline result " + result);
            }
            return new Pipeline(pipeline, pipelineLayout, descriptorLayout, capacity, maxBufferBytes, maxDispatchGroups);
        } catch (RuntimeException | Error error) {
            if (pipelineLayout != 0) vkDestroyPipelineLayout(vk, pipelineLayout, null);
            if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(vk, descriptorLayout, null);
            throw error;
        } finally {
            vkDestroyShaderModule(vk, shader, null);
        }
    }

    private static long createShader(VulkanDevice device, MemoryStack stack) throws IOException {
        String source;
        try (InputStream resource = VulkanIndexedVisibility.class.getResourceAsStream(SHADER)) {
            if (resource == null) throw new IOException("Missing " + SHADER);
            source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IOException("Could not initialize shaderc");
        long result = 0;
        try {
            // The shader uses core storage buffers, push constants and compute instructions.
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, SHADER, "main", 0);
            if (result == 0) throw new IOException("Shaderc returned no indexed visibility module");
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IOException(shaderc_result_get_error_message(result));
            }
            ByteBuffer code = shaderc_result_get_bytes(result);
            if (code == null) throw new IOException("Indexed visibility SPIR-V is missing");
            VkShaderModuleCreateInfo module = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer out = stack.callocLong(1);
            VulkanUtils.crashIfFailure(device, vkCreateShaderModule(device.vkDevice(), module, null, out), "Creating indexed visibility shader");
            return out.get(0);
        } finally {
            if (result != 0) shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    private static @Nullable BatchBuffer allocateBatch(VulkanDevice device, MemoryStack stack, int bytes,
                                                       List<IndexedDrawBatch.Candidate> candidates, int chunkSize) {
        VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        VmaAllocationCreateInfo options = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO)
                .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        LongBuffer handle = stack.callocLong(1);
        PointerBuffer allocation = stack.callocPointer(1);
        VmaAllocationInfo allocated = VmaAllocationInfo.calloc(stack);
        int result = vmaCreateBuffer(device.vma(), info, options, handle, allocation, allocated);
        if (result == VK_ERROR_DEVICE_LOST) VulkanUtils.crashIfFailure(device, result, "Allocating indexed visibility commands");
        if (result != VK_SUCCESS) return null;
        BatchBuffer buffer = new BatchBuffer(device, handle.get(0), allocation.get(0), bytes, chunkSize, candidates.size());
        try {
            if (allocated.pMappedData() == 0) {
                buffer.destroy();
                return null;
            }
            ByteBuffer data = MemoryUtil.memByteBuffer(allocated.pMappedData(), bytes);
            // Counted draws write to a separate output region ahead of the original commands.
            if (chunkSize != 0) data.position(candidates.size() * IndexedDrawBatch.COMMAND_BYTES);
            for (IndexedDrawBatch.Candidate candidate : candidates) candidate.draw().write(data);
            for (IndexedDrawBatch.Candidate candidate : candidates) {
                data.putFloat(candidate.x()).putFloat(candidate.y()).putFloat(candidate.z()).putFloat(candidate.radius());
            }
            return buffer;
        } catch (RuntimeException | Error error) {
            buffer.destroy();
            throw error;
        }
    }

    private static void record(VkCommandBuffer command, MemoryStack stack, Pipeline pipeline, BatchBuffer buffer,
                               int count, List<? extends Vector4fc> planes) {
        ByteBuffer constants = stack.calloc(CONSTANT_BYTES);
        for (int i = 0; i < planes.size(); i++) {
            Vector4fc plane = planes.get(i);
            int offset = i * 4 * Float.BYTES;
            constants.putFloat(offset, plane.x()).putFloat(offset + 4, plane.y())
                    .putFloat(offset + 8, plane.z()).putFloat(offset + 12, plane.w());
        }
        constants.putInt(CONSTANT_BYTES - 2 * Integer.BYTES, count);
        constants.putInt(CONSTANT_BYTES - Integer.BYTES, buffer.chunkSize);
        VkDescriptorBufferInfo.Buffer storage = VkDescriptorBufferInfo.calloc(1, stack);
        storage.buffer(buffer.vkBuffer()).offset(0).range(buffer.size());
        VkWriteDescriptorSet.Buffer descriptor = VkWriteDescriptorSet.calloc(1, stack);
        descriptor.sType$Default().dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).pBufferInfo(storage);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
        vkCmdPushDescriptorSetKHR(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout(), 0, descriptor);
        vkCmdPushConstants(command, pipeline.layout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
        int candidatesPerGroup = buffer.chunkSize == 0 ? GROUP_SIZE : buffer.chunkSize;
        vkCmdDispatch(command, Math.ceilDiv(count, candidatesPerGroup), 1, 1);

        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                0, barrier, null, null);
    }

    private record Pipeline(long handle, long layout, long descriptors, int maxCandidates, int maxBufferBytes,
                            int maxDispatchGroups) {
        void destroy(VkDevice device) {
            vkDestroyPipeline(device, handle, null);
            vkDestroyPipelineLayout(device, layout, null);
            vkDestroyDescriptorSetLayout(device, descriptors, null);
        }
    }

    private static final class BatchBuffer extends VulkanGpuBuffer {
        private final VulkanDevice device;
        private final long allocation;
        private final int chunkSize;
        private final long countsOffset;
        private boolean retired;
        private boolean closed;

        BatchBuffer(VulkanDevice device, long buffer, long allocation, long size, int chunkSize, int candidateCount) {
            super(buffer, GpuBuffer.USAGE_INDIRECT_PARAMETERS, size);
            this.device = device;
            this.allocation = allocation;
            this.chunkSize = chunkSize;
            this.countsOffset = (long) candidateCount * (BYTES_PER_CANDIDATE + IndexedDrawBatch.COMMAND_BYTES);
        }

        @Override public boolean isClosed() { return closed; }

        @Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            throw new UnsupportedOperationException("Indexed visibility commands are host managed");
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            retireAfterSubmit();
        }

        void retireAfterSubmit() {
            if (retired) return;
            retired = true;
            device.createCommandEncoder().queueForDestroy(this);
        }

        @Override public void destroy() {
            vmaDestroyBuffer(device.vma(), vkBuffer(), allocation);
            closed = true;
        }
    }
}
