layout(std140) uniform StarshipFrame {
    mat4 ViewProjection;
    vec4 PositionScale;
    vec4 RightStretch;
    vec4 UpTime;
    vec4 ForwardTexture;
    vec4 Warp;
};

#define CraftPosition PositionScale.xyz
#define ShipScale PositionScale.w
#define CraftRight RightStretch.xyz
#define WarpStretch RightStretch.w
#define CraftUp UpTime.xyz
#define Time UpTime.w
#define CraftForward ForwardTexture.xyz
#define HullTextureStrength ForwardTexture.w
#define WarpCharge Warp.x
#define WarpActive Warp.y
