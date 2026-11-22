float baseDensity(vec3 p, float rayDist, float timeTwist, vec3 flow) {
    vec3 q = p * vec3(1.0 / 21.0, 1.0 / 12.0, 1.0 / 15.0);
    float qLen2 = dot(q, q);
    if (qLen2 >= 1.266) return 0.0;
    float qLen = sqrt(qLen2);
    float envelope = qLen < .225 ? 1.0 : 1.0 - smoothstep(.35, 1.0, qLen + (noise(p * .32) - .5) * .25);
    if (envelope <= .001) return 0.0;

    float pY = p.y;
    float corridorBand = (1.0 - smoothstep(.30, 1.05, pY)) * smoothstep(-2.4, -.95, pY);
    float a = .035 * pY + timeTwist;
    float a2 = a * a;
    float sa = a * (1.0 - a2 * .166667);
    float ca = 1.0 - a2 * (.5 - a2 * .041667);
    vec2 twXZ = mat2(ca, -sa, sa, ca) * p.xz;

    vec2 canyon = vec2(twXZ.x, pY) - vec2(2.0 + sin(twXZ.y * .18) * .8, 2.0 + cos(twXZ.y * .12) * .8);
    float radius = length(canyon * vec2(.9, 1.2));

    float warpMorph = clamp(WarpActive + (WarpStretch - 1.0) * 0.55, 0.0, 1.3);
    if (warpMorph > 0.001) {
        float latDist = length(p - CraftPosition - CraftForward * dot(p - CraftPosition, CraftForward));
        radius += smoothstep(2.6, 0.6, latDist) * (0.85 * warpMorph);
    }

    if (radius < 1.48 && corridorBand <= .001) return 0.0;

    float opening = 3.35 + .65 * sin(twXZ.y * .32) + (noise(vec3(twXZ.x, pY, twXZ.y) * .65) - .5) * 1.1;
    float rift = smoothstep(opening - .65, opening + 1.15, radius);
    if (rift <= .001 && corridorBand <= .001) return 0.0;

    if (warpMorph > 0.001) {
        vec3 toP = p - CraftPosition;
        float along = dot(toP, CraftForward);
        float axialScale = mix(1.0, 0.15, clamp(warpMorph, 0.0, 1.0));
        vec3 lat = toP - CraftForward * along;
        float latDist = length(lat);
        vec3 latDir = latDist > 1e-4 ? lat / latDist : CraftRight;
        float push = smoothstep(2.0, 0.2, latDist) * (0.35 * warpMorph);
        vec3 warpShift = CraftForward * (WarpDistance * 0.65);
        p = CraftPosition + (lat + latDir * push) + CraftForward * (along * axialScale) + warpShift;
    }

    float broad = fbm(p * .145 + flow);
    vec3 detailCoord = p * .46 + flow * .46 + broad * 3.5;
    float detail = rayDist < 13.5 ? fbm(detailCoord) : fbmFar(detailCoord);
    float threads = rayDist < 15.5 ? 1.0 - abs(noise(p * .85 + flow * .85 + broad * 3.0) * 2.0 - 1.0) : .5;
    float folds = max(0.0, broad * .6 + detail * .5 + threads * .12 - .60) * 8.6;
    float wall = .10 * (1.0 - smoothstep(5.0, 9.0, radius)) * smoothstep(.32, .66, detail);
    float corridorMist = 0.0;
    if (corridorBand > .001 && rift < .999) {
        float camClear = smoothstep(.55, 1.85, length(p - Eye));
        float strata = smoothstep(.24, .76, broad * .55 + detail * .45 + .14 * sin(p.z * .52 + broad * 4.2));
        float wisps = .42 + .58 * smoothstep(.22, .75, detail * .60 + threads * .40);
        corridorMist = (.95 + 2.35 * strata) * wisps * corridorBand * (1.0 - rift) * camClear;
    }
    return ((folds + wall) * (.003 + .997 * rift) + corridorMist) * envelope;
}

// Fast 2-tap coarse occlusion field for blurred shadow marches.
float coarseDensity(vec3 p, float timeTwist, vec3 flow) {
    vec3 q = p * vec3(1.0 / 21.0, 1.0 / 12.0, 1.0 / 15.0);
    float qLen2 = dot(q, q);
    if (qLen2 >= 1.20) return 0.0;
    float envelope = qLen2 < .12 ? 1.0 : 1.0 - smoothstep(.35, 1.05, sqrt(qLen2));

    float pY = p.y;
    float corridorBand = (1.0 - smoothstep(.30, 1.05, pY)) * smoothstep(-2.4, -.95, pY);
    float a = .035 * pY + timeTwist;
    float a2 = a * a;
    float sa = a * (1.0 - a2 * .166667), ca = 1.0 - a2 * (.5 - a2 * .041667);
    vec2 twXZ = mat2(ca, -sa, sa, ca) * p.xz;

    vec2 canyon = vec2(twXZ.x, pY) - vec2(2.0 + sin(twXZ.y * .18) * .8, 2.0 + cos(twXZ.y * .12) * .8);
    float radius = length(canyon * vec2(.9, 1.2));

    float warpMorph = clamp(WarpActive + (WarpStretch - 1.0) * 0.55, 0.0, 1.3);
    if (warpMorph > 0.001) {
        float latDist = length(p - CraftPosition - CraftForward * dot(p - CraftPosition, CraftForward));
        radius += smoothstep(2.6, 0.6, latDist) * (0.85 * warpMorph);
    }

    if (radius < 2.25 && corridorBand <= .001) return 0.0;
    float rift = smoothstep(2.70, 4.50, radius);
    if (rift <= .001 && corridorBand <= .001) return 0.0;

    if (warpMorph > 0.001) {
        float axialScale = mix(1.0, 0.15, clamp(warpMorph, 0.0, 1.0));
        vec3 toP = p - CraftPosition;
        float along = dot(toP, CraftForward);
        vec3 warpShift = CraftForward * (WarpDistance * 0.65);
        p = CraftPosition + (toP - CraftForward * along) + CraftForward * (along * axialScale) + warpShift;
    }

    float broad = .14 + .72 * noise(p * .145 + flow);
    float detail = .15 + .70 * noise(p * .46 + flow * .46 + broad * 3.5);
    float folds = max(0.0, broad * .6 + detail * .5 + .06 - .60) * 8.6;
    float corridorMist = corridorBand > .001 ? (1.1 + 1.8 * smoothstep(.28, .74, broad * .55 + detail * .45)) * .70 * corridorBand * (1.0 - rift) : 0.0;
    return (folds * (.003 + .997 * rift) + corridorMist) * envelope;
}

// Seamless trilinear lookup into 144 KB 32^3 Worley normal+distance atlas (1-texel wrap border per slice).
float cellDistance(vec3 p, out vec3 normal) {
    vec2 wxy = fract(p.xy * .125) * 32.0 + 1.0;
    float wz = fract(p.z * .125 - (.5 / 32.0)) * 32.0;
    float z0 = floor(wz);
    float zf = wz - z0;
    float z1 = z0 > 30.5 ? 0.0 : z0 + 1.0;
    vec2 t0 = vec2(mod(z0, 8.0), floor(z0 * .125)) * 34.0 + wxy;
    vec2 t1 = vec2(mod(z1, 8.0), floor(z1 * .125)) * 34.0 + wxy;
    vec2 invAtlas = vec2(1.0 / 272.0, 1.0 / 136.0);
    vec4 s = mix(textureLod(CellSampler, t0 * invAtlas, 0.0),
                 textureLod(CellSampler, t1 * invAtlas, 0.0), zf);
    vec3 n = s.xyz * 2.0 - 1.0;
    normal = n * inversesqrt(max(dot(n, n), .0001));
    return s.w * 1.25;
}

void testWakeGroup(vec3 p, int startIdx, int endIdx, inout float closestDist2,
                   inout vec3 bestDiff, inout vec3 bestSeg, inout float bestSegLen2, inout float bestAge) {
    vec4 a = dataAt(startIdx, 0);
    for (int i = startIdx; i < endIdx; i++) {
        vec4 b = dataAt(i + 1, 0);
        vec3 seg = b.xyz - a.xyz;
        float len2 = max(dot(seg, seg), .00001);
        float u = clamp(dot(p - a.xyz, seg) / len2, 0.0, 1.0);
        vec3 diff = p - (a.xyz + seg * u);
        float dist2 = dot(diff, diff);
        if (dist2 < closestDist2) {
            closestDist2 = dist2;
            bestDiff = diff;
            bestSeg = seg;
            bestSegLen2 = len2;
            bestAge = mix(a.w, b.w, u);
        }
        a = b;
    }
}

void sampleWake(vec3 p, out vec3 warp, out vec3 disturbance, out vec3 wakeNormal) {
    warp = vec3(0.0);
    disturbance = vec3(0.0);
    wakeNormal = vec3(0.0, 1.0, 0.0);

    vec3 toHull = p - CraftPosition;
    vec3 local = mat3(CraftInverse) * toHull;
    float nearEyeFade = smoothstep(.55, 1.95, length(p - Eye));
    float hullDist2 = dot(toHull, toHull);
    if (hullDist2 < 5.76) {
        float hullDist = sqrt(hullDist2);
        float hullNear = (1.0 - smoothstep(.45, 2.25, hullDist)) * nearEyeFade;
        vec3 scaled = local / max(ShipScale, .01);
        float noseZ = scaled.z - .44;
        float flankR = length(scaled.xy * vec2(.85, 1.30));
        float bowShell = flankR * flankR + noseZ * .46 - .09;
        float openTop = 1.0 - .55 * smoothstep(.05, .45, scaled.y) * (1.0 - smoothstep(.08, .38, abs(scaled.x)));
        float bowBand = exp(-bowShell * bowShell / .032) * smoothstep(-1.45, .48, scaled.z) * openTop * hullNear;
        vec2 out2 = length(scaled.xy) > .001 ? normalize(scaled.xy) : vec2(1.0, 0.0);
        vec3 worldOut = transpose(mat3(CraftInverse)) * normalize(vec3(out2 * 1.25, .32));
        warp -= worldOut * (.36 * bowBand);
        disturbance.z = bowBand;
        wakeNormal = worldOut;
    }

    bool in0 = all(greaterThanEqual(p, WakeSubMin0)) && all(lessThanEqual(p, WakeSubMax0));
    bool in1 = all(greaterThanEqual(p, WakeSubMin1)) && all(lessThanEqual(p, WakeSubMax1));
    bool in2 = all(greaterThanEqual(p, WakeSubMin2)) && all(lessThanEqual(p, WakeSubMax2));
    if (!in0 && !in1 && !in2) return;

    float closestDist2 = 1e6, bestAge = 0.0, bestSegLen2 = 1.0;
    vec3 bestDiff = vec3(0.0, 1.0, 0.0), bestSeg = vec3(0.0, 0.0, -1.0);
    if (in0) testWakeGroup(p, 0, 8, closestDist2, bestDiff, bestSeg, bestSegLen2, bestAge);
    if (in1) testWakeGroup(p, 8, 16, closestDist2, bestDiff, bestSeg, bestSegLen2, bestAge);
    if (in2) testWakeGroup(p, 16, 23, closestDist2, bestDiff, bestSeg, bestSegLen2, bestAge);

    float wakeR = .15 + .165 * pow(max(bestAge, 0.0) + .05, .72);
    float maxReach = wakeR * 3.2;
    if (closestDist2 > maxReach * maxReach) return;

    float sternGate = (bestAge < 1.2 ? smoothstep(.24, -.08, local.z) : 1.0) * nearEyeFade;
    if (sternGate < .001) return;

    float closestDist = sqrt(max(closestDist2, .000001));
    vec3 flowAxis = -bestSeg * inversesqrt(bestSegLen2);
    vec3 sideAxis = cross(vec3(0.0, 1.0, 0.0), flowAxis);
    float sideLen2 = dot(sideAxis, sideAxis);
    sideAxis = sideLen2 > .0001 ? sideAxis * inversesqrt(sideLen2) : vec3(1.0, 0.0, 0.0);
    vec3 upAxis = cross(flowAxis, sideAxis);

    float sx = dot(bestDiff, sideAxis);
    float sy = dot(bestDiff, upAxis);

    vec3 eddyCoord = p * 1.06 - flowAxis * (Time * .46) + vec3(bestAge * .22, -Time * .16, bestAge * .14);
    float e0 = noise(eddyCoord);
    float e1 = noise(eddyCoord * 2.13 + vec3(4.7, 2.3, 8.1));
    vec3 eddy = vec3(e0 * .62 + e1 * .38, (1.0 - e0) * .54 + e1 * .46, e1 * .68 + e0 * .32) - .5;

    float spreadStage = smoothstep(.10, 9.0, bestAge);
    float nearBoost = 1.0 + .75 * exp(-bestAge * .30);
    float lifeFade = exp(-bestAge * .066) * (1.0 - smoothstep(13.5, 20.5, bestAge)) * sternGate;

    float coreSep = wakeR * .98;
    float vortexR = wakeR * .84;
    vec2 dR = vec2(sx - coreSep, sy + wakeR * .06);
    vec2 dL = vec2(sx + coreSep, sy + wakeR * .06);
    float invVortex2 = 1.0 / max(vortexR * vortexR, .001);
    float wR = exp(-dot(dR, dR) * invVortex2);
    float wL = exp(-dot(dL, dL) * invVortex2);
    vec2 swirl2D = vec2(-dR.y, dR.x) * wR + vec2(dL.y, -dL.x) * wL;
    vec3 swirl3D = (sideAxis * swirl2D.x + upAxis * swirl2D.y) * (1.55 * (.35 + .65 * spreadStage));

    float shedPhase = bestAge * 2.45 - Time * 1.70;
    float invR = 1.0 / max(wakeR, .05);
    float nx = sx * invR + sin(shedPhase + eddy.z * 3.2) * (.22 * spreadStage)
             + eddy.x * (.52 + .88 * spreadStage) + swirl2D.x * invR * .28;
    float ny = sy * invR + cos(shedPhase * .81 + eddy.x * 2.8) * (.18 * spreadStage)
             + eddy.y * (.44 + .75 * spreadStage) + swirl2D.y * invR * .28;
    float normR2 = nx * nx * .5625 + ny * ny;

    vec3 radialDir = bestDiff / max(closestDist, .001);
    vec3 lateralPush = sideAxis * sign(sx + .0001) * exp(-nx * nx * .55) * (wakeR * .95);
    float slipstream = exp(-normR2 * 1.25) * (.52 * wakeR);
    vec3 eddyWorld = (sideAxis * eddy.x + upAxis * eddy.y + flowAxis * eddy.z) * (wakeR * (.68 + .98 * spreadStage));
    warp -= (lateralPush + radialDir * (wakeR * .42 * exp(-normR2 * .8)) + swirl3D + flowAxis * slipstream - eddyWorld * .75) * lifeFade;

    float breakup = smoothstep(.14, .74, .54 + eddy.x * 1.15 + eddy.z * .65 - .28 * smoothstep(9.5, 20.0, bestAge));
    float troughX = exp(-nx * nx * .92);
    float troughY = smoothstep(-1.45, -.50, ny) * (1.0 - smoothstep(1.30, 2.25, ny));
    float cavity = troughX * troughY * min(1.0, lifeFade * 1.35) * (.80 + .20 * breakup);

    float crestHook = 1.44 - .30 * smoothstep(.10, .95, ny);
    vec2 wallDelta = vec2((abs(nx) - crestHook) / (.48 + .24 * spreadStage), (ny - .16) / (.82 + .28 * spreadStage));
    float openCenter = smoothstep(.26, .84 - .30 * spreadStage, abs(nx));
    float flankWall = exp(-dot(wallDelta, wallDelta) * 1.35) * openCenter;
    float curlWave = .52 + eddy.x * 1.12 + eddy.y * .82 + .32 * sin(shedPhase + sign(sx) * 1.45);
    float billow = flankWall * (.62 + .80 * (wR + wL)) * smoothstep(.12, .68, curlWave);
    float ridge = billow * lifeFade * nearBoost * (.36 + .64 * breakup);

    disturbance.x = max(disturbance.x, cavity);
    disturbance.y = max(disturbance.y, ridge);
    vec3 ridgeDir = normalize(sideAxis * sign(sx + .0001) * .65 + upAxis * (.48 + .28 * (wR + wL)) + (sideAxis * eddy.x + upAxis * eddy.y) * .95);
    wakeNormal = normalize(mix(wakeNormal, ridgeDir, clamp(ridge * 1.45, 0.0, 1.0)));
}

float shadowDensity(vec3 p, float timeTwist, vec3 flow) {
    if (InteractionStrength > .5
            && all(greaterThanEqual(p, WakeBoundsMin))
            && all(lessThanEqual(p, WakeBoundsMax))) {
        vec3 warp, disturbance, wakeNormal;
        sampleWake(p, warp, disturbance, wakeNormal);
        float d = coarseDensity(p + warp, timeTwist, flow);
        float ambient = smoothstep(.08, 1.15, d);
        d *= 1.0 - clamp(disturbance.x * .97, 0.0, .98);
        d = d * (1.0 + disturbance.y * 2.0 + disturbance.z * 1.4)
          + disturbance.y * (.32 + 1.85 * ambient) + disturbance.z * (.24 + 1.55 * ambient);
        return max(d, 0.0);
    }
    return max(coarseDensity(p, timeTwist, flow), 0.0);
}

float density(vec3 p, float rayDist, bool checkWake, float transmittance, float timeTwist, vec3 flow,
              out vec3 disturbance, out vec3 normal, out float smokeDensity) {
    vec3 warp = vec3(0.0), wakeNormal = vec3(0.0, 1.0, 0.0);
    disturbance = vec3(0.0);
    if (checkWake) sampleWake(p, warp, disturbance, wakeNormal);
    vec3 q = p + warp;
    float d = baseDensity(q, rayDist, timeTwist, flow);
    if (checkWake) {
        float cavity = disturbance.x, ridge = disturbance.y, bow = disturbance.z;
        vec3 hullLocal = (mat3(CraftInverse) * (p - CraftPosition)) / max(ShipScale, .01);
        vec3 hullNorm = hullLocal / vec3(.36, .28, .66);
        float hullClear = exp(-dot(hullNorm, hullNorm) * 1.5);
        float ambient = smoothstep(.08, 1.20, d);
        d *= 1.0 - clamp(cavity * .97 + hullClear * .95, 0.0, .98);
        d = d * (1.0 + ridge * 2.2 + bow * 1.5)
          + ridge * (.36 + 2.05 * ambient) + bow * (.26 + 1.70 * ambient);
    }
    normal = vec3(0.0, 1.0, 0.0);
    if (d > .035 && transmittance > .08 && DetailStrength > .5) {
        float detailFade = 1.0 - smoothstep(12.0, 32.0, rayDist);
        if (detailFade > .01) {
            float warpMorph = clamp(WarpActive + (WarpStretch - 1.0) * 0.55, 0.0, 1.3);
            if (warpMorph > 0.001) {
                float axialScale = mix(1.0, 0.15, clamp(warpMorph, 0.0, 1.0));
                vec3 toQ = q - CraftPosition;
                float along = dot(toQ, CraftForward);
                vec3 warpShift = CraftForward * (WarpDistance * 0.65);
                q = CraftPosition + (toQ - CraftForward * along) + CraftForward * (along * axialScale) + warpShift;
            }
            float cells = cellDistance(q * 1.45 + vec3(Time * .009, 0.0, Time * .004), normal);
            float fine = noise(q * 4.2 + vec3(0.0, Time * .035, 0.0));
            float erosion = smoothstep(.22, .78, cells) * (.16 + .12 * fine) * (1.0 + .65 * disturbance.y);
            float fineFade = (1.0 - smoothstep(2.8, 8.2, rayDist)) * step(.20, transmittance) * step(.12, d);
            if (fineFade > .01) {
                vec3 fineNormal;
                float fineCells = cellDistance(q * 3.4 + vec3(Time * .012, Time * .006, 0.0), fineNormal);
                d *= 1.0 - fineFade * .52 * smoothstep(.18, .76, fineCells);
                vec3 blend = mix(normal, fineNormal, fineFade * .60);
                normal = blend * inversesqrt(max(dot(blend, blend), .0001));
            }
            d = max(0.0, d - erosion * detailFade) * (1.0 + detailFade * .38 * (1.0 - cells));
        }
    }
    if (checkWake) {
        float macroWeight = clamp(disturbance.y * 1.05 + disturbance.z * 1.1, 0.0, .78);
        vec3 mixed = mix(normal, wakeNormal, macroWeight);
        normal = mixed * inversesqrt(max(dot(mixed, mixed), .0001));
    }
    smokeDensity = warpSmokeDensity(p);
    return max(d, 0.0) + smokeDensity;
}

vec2 rayBox(vec3 ro, vec3 invRd, vec3 bMin, vec3 bMax) {
    vec3 t0 = (bMin - ro) * invRd;
    vec3 t1 = (bMax - ro) * invRd;
    vec3 near3 = min(t0, t1), far3 = max(t0, t1);
    return vec2(max(near3.x, max(near3.y, near3.z)), min(far3.x, min(far3.y, far3.z)));
}

bool sampleCraft(vec2 uv, vec3 ro, vec3 rd, out float hit, out vec3 p,
                 out vec4 surface, out vec4 normalData, out vec4 pbr) {
    hit = 1e6;
    surface = vec4(0.0);
    normalData = vec4(0.0, 1.0, 0.0, 1.0);
    pbr = vec4(0.0, .8, 1.0, 0.0);
    if (CraftVisible < .5) return false;
    float depth = textureLod(CraftDepthSampler, uv, 0.0).r;
    if (!craftDepthPresent(depth)) return false;
    vec4 viewPoint = InverseProjection * vec4(uv * 2.0 - 1.0, craftClipDepth(depth), 1.0);
    if (abs(viewPoint.w) < 1e-6) return false;
    viewPoint.xyz /= viewPoint.w;
    vec3 worldPoint = (InverseView * vec4(viewPoint.xyz, 1.0)).xyz;
    vec3 delta = worldPoint - ro;
    hit = dot(delta, rd);
    if (hit <= .001 || hit > 1e5) return false;
    p = worldPoint;
    surface = textureLod(CraftSurfaceSampler, uv, 0.0);
    normalData = textureLod(CraftNormalSampler, uv, 0.0);
    pbr = textureLod(CraftPbrSampler, uv, 0.0);
    if (dot(normalData.xyz, normalData.xyz) < .25) return false;
    normalData.xyz = normalize(normalData.xyz);
    return true;
}
