package lib.kasuga.registration.data_driven.builder;

import net.minecraft.server.packs.resources.IoSupplier;

import java.io.Reader;

record RegistrationFile(String path, IoSupplier<Reader> reader) {}
