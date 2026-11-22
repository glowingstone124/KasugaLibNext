void main() {
    vec3 local = Position * ShipScale;
    local.z *= max(WarpStretch, 1.0);
    vec3 world = CraftPosition
        + CraftRight * local.x
        + CraftUp * local.y
        + CraftForward * local.z;
    vec3 basisNormal = CraftRight * Normal.x + CraftUp * Normal.y
        + CraftForward * (Normal.z / max(WarpStretch, 1.0));
    vLocal = Position;
    vWorld = world;
    vNormal = normalize(basisNormal);
    vUv = UV;
    vColor = Color;
    vMaterial = Material;
    gl_Position = ViewProjection * vec4(world, 1.0);
}
