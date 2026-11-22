vec4 volume(vec3 ro, vec3 rd, vec3 invRd, float nearT, float farT) {
    float span = farT - nearT;
    float jitter = .25 + .50 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(.06711056, .00583715))));
    vec2 wakeSpan = InteractionStrength > .5 ? rayBox(ro, invRd, WakeBoundsMin, WakeBoundsMax) : vec2(1e6, -1e6);
    bool rayHitsWake = wakeSpan.y >= max(wakeSpan.x, nearT);

    vec4 light0 = LightCount > 0 ? dataAt(24, 0) : vec4(0.0);
    vec4 light1 = LightCount > 1 ? dataAt(25, 0) : vec4(0.0);
    float timeTwist = .13 * sin(Time * .045);
    vec3 flow = vec3(Time * .016, -Time * .023, Time * .009) + CraftForward * WarpDistance;

    float transmittance = 1.0, visibility = 1.0, previousDensity = 0.0;
    int shadowTick = 0, emptyRun = 0;
    vec3 color = vec3(0.0);
    const float invSteps = 1.0 / float(MARCH_STEPS);
    for (int i = 0; i < MARCH_STEPS; i++) {
        float uMid = (float(i) + jitter) * invSteps;
        float t = nearT + span * (uMid * (.12 + .88 * uMid));
        bool checkWake = rayHitsWake && t >= wakeSpan.x && t <= wakeSpan.y;
        vec3 p = ro + rd * t;
        vec3 disturbance, normal;
        float smokeDensity;
        float d = density(p, t, checkWake, transmittance, timeTwist, flow, disturbance, normal, smokeDensity);
        if (d <= .002) {
            previousDensity = 0.0;
            if (!checkWake && emptyRun >= 1 && i + 1 < MARCH_STEPS) {
                float uNext = (float(i + 1) + jitter) * invSteps;
                float tNext = nearT + span * (uNext * (.12 + .88 * uNext));
                if (!rayHitsWake || tNext < wakeSpan.x || tNext > wakeSpan.y) {
                    emptyRun = 2;
                    i++;
                    continue;
                }
            }
            emptyRun = 1;
            continue;
        }
        if (emptyRun == 2) {
            emptyRun = -1;
            i -= 2;
            continue;
        }
        emptyRun = 0;

        float u0 = float(i) * invSteps, u1 = float(i + 1) * invSteps;
        float stepSize = span * (u1 * (.12 + .88 * u1) - u0 * (.12 + .88 * u0));
        vec3 toCore = CORE - p;
        float coreDist2 = max(dot(toCore, toCore), .0001);
        vec3 lightDirection = toCore * inversesqrt(coreDist2);
        if ((shadowTick++ & 3) == 0 || previousDensity <= .002) {
            float occlusion = shadowDensity(p + lightDirection * .62, timeTwist, flow) * 1.15
                            + coarseDensity(p + lightDirection * 3.4, timeTwist, flow) * 4.1;
            visibility = exp(-occlusion * 1.35);
        }
        float micro = DetailStrength > .5 ? .36 + .92 * max(dot(normal, lightDirection), 0.0) : 1.0;
        float rdDotL = max(dot(rd, lightDirection), 0.0);
        float rdDotL2 = rdDotL * rdDotL;
        float phase = .24 + 1.05 * (rdDotL2 * rdDotL2 * sqrt(rdDotL));
        float tint = smoothstep(-12.0, 14.0, p.x + p.z * .45);
        vec3 dust = mix(vec3(.16, .09, .07), vec3(.06, .11, .18), tint);
        vec3 emission = dust * .12 + vec3(.048, .072, .112) * visibility;
        float rimFill = .18 + .82 * max(dot(normal, vec3(-.520, .832, -.208)), 0.0);
        emission += vec3(.042, .068, .102) * rimFill * (.28 + .72 * visibility);
        float wakeScatter = 1.0 + disturbance.y * .75 + disturbance.z * 1.10;
        emission += mix(vec3(.65, .31, .15), vec3(.27, .66, 1.35), tint) * CoreStrength
                * visibility * visibility * phase * micro * wakeScatter * (78.0 / (8.0 + coreDist2));
        emission += vec3(.045, .085, .140) * (disturbance.y * .55 + disturbance.z * .95) * (.20 + .80 * visibility) * CoreStrength;
        // Warp smoke has its own dust-scattering response. It stays local to the
        // advected medium and inherits the same cloud shadow, so it reads as a
        // rolling trail instead of a screen-wide emissive band.
        float smokeLight = 1.0 - exp(-smokeDensity * .85);
        vec3 smokeTint = mix(vec3(.20, .16, .15), vec3(.14, .28, .38), tint);
        emission += smokeTint * smokeLight * (.46 + .66 * micro)
                * (.48 + .52 * visibility) * (.58 + .42 * CoreStrength);
        float wakeDust = clamp(disturbance.y * 2.0 + disturbance.z * 1.1, 0.0, 1.5);
        vec3 wakeDustTint = mix(vec3(.23, .18, .15), vec3(.16, .31, .42), tint);
        emission += wakeDustTint * wakeDust * (.18 + .34 * micro)
                * (.42 + .58 * visibility) * CoreStrength;
        emission += vec3(.58, .78, 1.0) * GlobalFlash * (.28 + .85 * visibility) * (.25 + .75 * micro * micro);
        if (LightCount > 0) {
            vec3 delta0 = p - light0.xyz;
            emission += vec3(.24, .44, .85) * light0.w * 4.0 / (1.0 + dot(delta0, delta0) * .8);
            if (LightCount > 1) {
                vec3 delta1 = p - light1.xyz;
                emission += vec3(.24, .44, .85) * light1.w * 4.0 / (1.0 + dot(delta1, delta1) * .8);
            }
        }
        // Dust has extinction and scattered light; it is not a self-emissive cyan tunnel.
        emission *= 1.0 - WarpActive * .20;
        emission += vec3(.014, .020, .028) * (d / (1.0 + d)) * IndirectStrength;
        float opacity = 1.0 - exp(-d * stepSize * .10);
        color += transmittance * opacity * emission;
        transmittance *= 1.0 - opacity;
        previousDensity = d;
        if (transmittance < .02) break;
    }
    return vec4(color, transmittance);
}

vec2 segmentRay(vec3 ro, vec3 rd, vec3 a, vec3 b) {
    vec3 v = b - a, w = ro - a;
    float vv = max(dot(v, v), .00001), rv = dot(rd, v);
    float rayT = max((rv * dot(v, w) - vv * dot(rd, w)) / max(vv - rv * rv, .00001), 0.0);
    float u = clamp((dot(v, w) + rv * rayT) / vv, 0.0, 1.0);
    vec3 onSegment = a + v * u;
    rayT = max(dot(onSegment - ro, rd), 0.0);
    return vec2(length(ro + rd * rayT - onSegment), rayT);
}

vec3 nearDust(vec3 ro, vec3 rd, float maxT) {
    if (DustCount <= 0) return vec3(0.0);
    vec3 color = vec3(0.0), streak = CraftVelocity * .028;
    for (int i = 0; i < 32; i++) {
        if (i >= DustCount) break;
        vec4 d = dataAt(32 + i, 3);
        vec3 rel = d.xyz - ro;
        float approxT = dot(rel, rd);
        if (approxT < .04 || approxT > maxT) continue;
        vec2 hit = segmentRay(ro, rd, d.xyz, d.xyz - streak);
        if (hit.y < .08 || hit.y > maxT) continue;
        float width = max(.006, hit.y * .0012);
        float d2 = hit.x * hit.x / (width * width);
        if (d2 < 8.0) color += vec3(.10, .14, .19) * (exp(-d2 * 1.5) + .10 * exp(-d2 * .35)) * d.w;
    }
    return color;
}

vec3 lightning(vec3 ro, vec3 rd, float maxT) {
    if (SegmentCount <= 0) return vec3(0.0);
    vec3 color = vec3(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= SegmentCount) break;
        vec4 s0 = dataAt(i, 1);
        vec4 s1 = dataAt(i, 2);
        vec3 a = s0.xyz, b = s1.xyz;
        float energy = s0.w, width = s1.w;
        vec3 v = b - a, w = ro - a;
        float vv = max(dot(v, v), .00001), rv = dot(rd, v);
        float rayT = max((rv * dot(v, w) - vv * dot(rd, w)) / max(vv - rv * rv, .00001), 0.0);
        float segmentT = clamp((dot(v, w) + rv * rayT) / vv, 0.0, 1.0);
        vec3 onSegment = a + v * segmentT;
        rayT = max(dot(onSegment - ro, rd), 0.0);
        if (rayT > maxT) continue;
        vec3 off = ro + rd * rayT - onSegment;
        float dist2 = dot(off, off);
        float distance = sqrt(dist2);
        float thickness = max(.018 * width, rayT * .0012);
        float core = 1.0 - smoothstep(thickness * .4, thickness * 1.4, distance);
        float halo = .004 * width / (dist2 + .07);
        color += (vec3(.85, .94, 1.0) * core * 3.5 + vec3(.19, .36, 1.0) * halo) * energy;
    }
    return color;
}

vec3 starlight(vec3 ro, vec3 rd, float maxT) {
    if (StarCount <= 0) return vec3(0.0);
    vec3 color = vec3(0.0);
    float warpStreakLen = clamp(WarpStretch - 1.0, 0.0, 2.0) * .12 + WarpActive * .20;
    for (int i = 0; i < 32; i++) {
        if (i >= StarCount) break;
        vec4 star = dataAt(i, 3);
        if (warpStreakLen > 0.02) {
            vec3 p0 = star.xyz;
            vec3 p1 = star.xyz - CraftForward * warpStreakLen;
            vec2 hit = segmentRay(ro, rd, p0, p1);
            if (hit.y > 0.05 && hit.y < maxT) {
                float radius = max(0.014, hit.y * 0.0006);
                float d2 = hit.x * hit.x / (radius * radius);
                if (d2 < 6.0) {
                    float core = exp(-d2 * 1.4);
                    float glow = 0.0015 / (hit.x * hit.x + 0.025);
                    color += mix(vec3(0.4, 0.7, 1.0), vec3(0.9, 0.95, 1.0), core) * (core * .20 + glow * .16) * star.w;
                }
            }
        } else {
            vec3 delta = star.xyz - ro;
            float rayT = dot(delta, rd);
            if (rayT <= 0.0 || rayT > maxT) continue;
            vec3 perp = delta - rd * rayT;
            float d2 = dot(perp, perp);
            if (d2 > .55) continue;
            float radius = max(.028, rayT * .0011);
            float r2 = radius * radius;
            float core = d2 < r2 * 8.0 ? exp(-d2 / r2) : 0.0;
            float glow = .0015 / (d2 + .03);
            color += vec3(.6, .74, 1.0) * (core + glow) * star.w;
        }
    }
    return color;
}

vec3 coreRadiance(vec3 ro, vec3 rd) {
    vec3 delta = CORE - ro;
    float along = dot(delta, rd);
    if (along <= 0.0) return vec3(0.0);
    vec3 perp = delta - rd * along;
    float d2 = dot(perp, perp);
    float halo = .15 * exp(-d2 / 4.0) + .035 / (d2 + .15);
    float disk = d2 < .5 ? 4.0 * exp(-d2 / .045) : 0.0;
    return (vec3(.22, .48, 1.0) * halo + vec3(.78, .90, 1.0) * disk) * CoreStrength;
}

vec3 filmic(vec3 x) {
    x = max(x, vec3(0.0));
    return clamp((x * (2.51 * x + .03)) / (x * (2.43 * x + .59) + .14), 0.0, 1.0);
}

vec3 warpParallaxStars(vec3 ro, vec3 rd, float maxT) {
    float warpEffect = clamp(WarpActive * 1.15 + smoothstep(1.45, 2.70, WarpStretch) * 0.60, 0.0, 1.3);
    if (warpEffect < 0.02) return vec3(0.0);

    float rdDotF = dot(rd, CraftForward);
    float poleFade = smoothstep(0.992, 0.935, abs(rdDotF));
    if (poleFade <= 0.001) return vec3(0.0);

    vec3 dOrig = ro - CraftPosition;
    float dDotF = dot(dOrig, CraftForward);
    vec3 dPerp = dOrig - CraftForward * dDotF;

    vec3 rdPerp = rd - CraftForward * rdDotF;
    float A = dot(rdPerp, rdPerp);
    if (A < 1e-5) return vec3(0.0);

    float B = dot(dPerp, rdPerp);
    float dPerp2 = dot(dPerp, dPerp);

    vec3 color = vec3(0.0);
    const vec3 radii = vec3(2.4, 5.6, 12.0);
    const vec3 speeds = vec3(1.5, 1.1, 0.70);
    const vec3 scales = vec3(1.1, 0.80, 0.55);

    for (int shell = 0; shell < 3; shell++) {
        float R = radii[shell];
        float C = dPerp2 - R * R;
        float disc = B * B - A * C;
        if (disc <= 0.0) continue;
        float t = (-B + sqrt(disc)) / A;
        if (t <= 0.05 || t > maxT) continue;

        vec3 hitP = ro + rd * t;
        vec3 toHit = hitP - CraftPosition;
        float zAlong = dot(toHit, CraftForward);

        float uX = dot(toHit, CraftRight);
        float uY = dot(toHit, CraftUp);
        float phi = atan(uY, uX);
        float phiNorm = (phi + 3.14159265) * 0.15915494;
        float nPhi = 14.0 + float(shell) * 6.0;

        float shellSpeed = speeds[shell];
        float zTravel = zAlong + WarpDistance * (shellSpeed * 1.8);
        float cellH = 3.2;

        vec2 grid = vec2(phiNorm * nPhi, zTravel / cellH);
        vec2 cellId = floor(grid);
        vec2 cellFrac = fract(grid);

        float h = fract(sin(dot(cellId + float(shell) * 17.3, vec2(12.9898, 78.233))) * 43758.5453);
        if (h > 0.12) continue;

        float starFracX = fract(h * 37.1);
        float cellW = (6.2831853 * R) / nPhi;
        float angDist = abs(cellFrac.x - starFracX) * cellW;
        float streakW = max(0.004, t * 0.0006);
        float lineCore = exp(-(angDist * angDist) / (streakW * streakW * 2.5));

        float zRel = cellFrac.y;
        float trailSpan = mix(0.04, 0.16, clamp(warpEffect, 0.0, 1.0));
        float zFade = smoothstep(0.0, 0.08, zRel) * (1.0 - smoothstep(0.08, trailSpan, zRel));

        float atten = (1.0 / (1.0 + t * 0.04)) * scales[shell];
        vec3 starCol = mix(vec3(0.20, 0.35, .55), vec3(0.55, 0.66, .78), clamp(h * 2.8, 0.0, 1.0));
        color += starCol * (lineCore * zFade * atten * warpEffect * poleFade * .22);
    }
    return color;
}

vec3 nacelleParticles(vec3 ro, vec3 rd, float maxT) {
    if (NacelleParticleCount <= 0) return vec3(0.0);
    // During the actual warp cruise the analytic plume below replaces these
    // discrete sprite-like sparks; keeping both layers makes the exhaust read as
    // detached blue rectangles.
    if (WarpActive > .15 || WarpStretch > 1.25) return vec3(0.0);
    vec3 sc = NacelleParticleSphere.xyz;
    vec3 dsc = ro - sc;
    float b = dot(dsc, rd);
    float c = dot(dsc, dsc) - NacelleParticleSphere.w;
    if (c > 0.0 && b > 0.0) return vec3(0.0);
    float disc = b * b - c;
    if (disc < 0.0) return vec3(0.0);
    float tNear = -b - sqrt(disc);
    if (tNear > maxT) return vec3(0.0);

    vec3 color = vec3(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= NacelleParticleCount) break;
        vec4 h = dataAt(i, 4);
        vec4 t = dataAt(i, 5);
        float brightness = h.w;
        if (brightness <= 0.002) continue;
        vec3 p0 = h.xyz;
        vec3 p1 = t.xyz;
        float radius = t.w;

        vec3 mid = (p0 + p1) * 0.5;
        float approxT = dot(mid - ro, rd);
        if (approxT < 0.0 || approxT > maxT) continue;
        vec3 probe = ro + rd * approxT;
        vec3 dMid = probe - mid;
        float maxR = radius * 3.2 + 0.12;
        if (dot(dMid, dMid) > maxR * maxR) continue;

        vec2 hit = segmentRay(ro, rd, p0, p1);
        if (hit.y > 0.02 && hit.y < maxT) {
            float w = max(radius, hit.y * 0.001);
            float d2 = hit.x * hit.x / (w * w);
            if (d2 < 6.5) {
                float core = exp(-d2 * 3.0) * 1.25;
                float halo = exp(-d2 * 1.2) * 0.07;
                // Keep a blue core; the old unbounded mix factor extrapolated overlapping sparks to white.
                vec3 pCol = mix(vec3(0.025, 0.12, 0.36), vec3(0.12, 0.38, 0.72), clamp(core * 0.6, 0.0, 1.0));
                color += pCol * ((core + halo) * brightness);
            }
        }
    }
    return color * .34 / (1.0 + max(color.r, max(color.g, color.b)) * 0.55);
}

// Localized two-nozzle plume plus a converging rear wake. The first part anchors
// the effect to the actual nacelles; the second part makes the long back trail a
// continuous, broken smoke body instead of two detached screen-space streaks.
vec3 warpEnginePlume(vec3 ro, vec3 rd, float maxT) {
    float drive = clamp(WarpActive + smoothstep(1.12, 2.70, WarpStretch) * .88
            + WarpCharge * .14, 0.0, 1.30);
    if (drive < .035) return vec3(0.0);

    float drive01 = smoothstep(.08, 1.0, drive);
    float reach = mix(.72, 1.72, drive01);
    vec3 color = vec3(0.0);
    for (int engine = 0; engine < 2; engine++) {
        float side = engine == 0 ? -1.0 : 1.0;
        vec3 nozzle = CraftPosition
                + CraftRight * (side * .255 * ShipScale)
                + CraftUp * (.115 * ShipScale)
                - CraftForward * (.56 * ShipScale);
        for (int puff = 0; puff < 12; puff++) {
            float u = (float(puff) + .5) / 12.0;
            float phase = Time * (.72 + .10 * drive) + u * 7.4 + side * 1.7;
            float radius = mix(.050, .165, u) * (.72 + .28 * drive);
            float travel = .05 + reach * u;
            vec3 center = nozzle - CraftForward * travel;
            // Let the two nozzle strands merge downstream. The near field keeps
            // the engine separation readable; the far field becomes one wake.
            float merge = smoothstep(.16, .74, u);
            center += CraftRight * (-side * .18 * ShipScale * merge);
            float curl = sin(phase * 1.17 + u * 8.0) * (.010 + .16 * u)
                    + sin(phase * .43 - u * 11.0) * (.006 + .08 * u);
            float lift = cos(phase * .91 - u * 6.0) * (.008 + .12 * u)
                    + cos(phase * .37 + u * 9.0) * (.005 + .06 * u);
            center += CraftRight * curl;
            center += CraftUp * lift;

            vec2 hit = segmentRay(ro, rd,
                    center + CraftForward * (radius * .72),
                    center - CraftForward * (radius * .72));
            if (hit.y < .02 || hit.y > maxT) continue;
            float d2 = hit.x * hit.x / max(radius * radius, 1e-5);
            float noiseField = noise(center * (2.6 + u * 1.4) + vec3(Time * .035, -Time * .022, 1.7));
            float breakup = .58 + .62 * smoothstep(.18, .84, noiseField);
            float life = smoothstep(.0, .05, u) * (1.0 - smoothstep(.88, 1.0, u));
            float blob = exp(-d2 * (1.18 + .45 * u)) * life * breakup;
            float body = exp(-d2 * .30) * life * (.11 + .15 * noiseField);
            float coolTrail = smoothstep(.18, .62, u);
            float nearFade = 1.0 - smoothstep(.40, .78, u);
            vec3 plumeColor = mix(vec3(.58, .23, .08), vec3(.12, .25, .31), coolTrail);
            color += plumeColor * (blob * nearFade * (.22 + .30 * drive) + body * (.08 + .08 * drive));
        }
    }

    // A low-saturation central smoke spine makes the rearward path survive in
    // dense nebula backgrounds. It is intentionally dimmer than the nozzle core.
    float wakeReach = mix(1.30, 5.35, drive01);
    for (int puff = 0; puff < 18; puff++) {
        float u = (float(puff) + .5) / 18.0;
        float travel = .32 + wakeReach * u;
        float phase = Time * .33 + u * 6.1 + WarpTunnelPhase * .009;
        float backRadius = mix(.08, .31, u) * (.78 + .22 * drive01);
        vec3 center = CraftPosition - CraftForward * travel;
        center += CraftRight * sin(phase * .83 + u * 4.0) * (.025 + .16 * u);
        center += CraftUp * cos(phase * .69 - u * 5.4) * (.018 + .11 * u);
        float turbulence = noise(center * (1.35 + u * .75) + vec3(Time * .025, -Time * .018, 4.1));
        float breakMask = smoothstep(.38, .80, turbulence);
        float gapMask = smoothstep(.28, .76,
                noise(center * (2.25 + u * .55) + vec3(-Time * .021, Time * .017, 8.3)));
        vec2 hit = segmentRay(ro, rd,
                center + CraftForward * (backRadius * .64),
                center - CraftForward * (backRadius * .64));
        if (hit.y < .02 || hit.y > maxT) continue;
        float d2 = hit.x * hit.x / max(backRadius * backRadius, 1e-5);
        float life = smoothstep(.0, .04, u) * (1.0 - smoothstep(.90, 1.0, u));
        float puffWeight = exp(-d2 * (.92 + .42 * u)) * life
                * (.05 + 1.10 * breakMask) * (.25 + .75 * gapMask);
        float rim = exp(-d2 * .28) * life * (.035 + .08 * turbulence) * gapMask;
        vec3 smokeColor = mix(vec3(.055, .09, .12), vec3(.12, .20, .24), smoothstep(.24, .80, turbulence));
        color += smokeColor * (puffWeight * (.075 + .07 * drive01) + rim * .035);
    }

    float peak = max(color.r, max(color.g, color.b));
    return color * (.70 / (1.0 + peak * .46));
}

vec3 warpFlashEffect(vec3 ro, vec3 rd, bool hitCraft) {
    if (WarpFlash < 0.01) return vec3(0.0);
    vec3 bowP = CraftPosition + CraftForward * (0.62 * max(WarpStretch, 1.0) * ShipScale);
    vec3 bowDir = normalize(bowP - ro);
    float cosAngle = dot(rd, bowDir);
    if (cosAngle < 0.0) return vec3(0.0);
    float angDist2 = 1.0 - cosAngle * cosAngle;
    float core = exp(-angDist2 * 650.0);
    vec3 ortho = rd - bowDir * cosAngle;
    float rx = dot(ortho, CraftRight);
    float ry = dot(ortho, CraftUp);
    float streak = exp(-ry * ry * 5000.0) * exp(-rx * rx * 220.0);
    vec3 col = mix(vec3(0.25, 0.65, 1.2), vec3(0.9, 0.96, 1.0), WarpFlash);
    float occ = hitCraft ? 0.15 : 1.0;
    return col * ((core * .38 * occ + streak * .045) * (WarpFlash * 0.25));
}

void main() {
    vec4 view = InverseProjection * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize((InverseView * vec4(normalize(view.xyz / max(abs(view.w), .00001)), 0.0)).xyz);
    vec3 ro = Eye;

    // 1. Sample the standalone OBJ color/normal/depth buffers rendered with this exact optical view.
    vec3 craftPoint = vec3(0.0);
    vec4 craftMaterial = vec4(0.0);
    vec4 craftNormal = vec4(0.0, 1.0, 0.0, 0.0);
    vec4 craftPbr = vec4(0.0, .8, 1.0, 0.0);
    float hit = 1e6;
    bool hitCraft = sampleCraft(texCoord, ro, rd, hit, craftPoint, craftMaterial, craftNormal, craftPbr);
    vec3 craftCol = hitCraft ? craftSurface(craftPoint, craftMaterial, craftNormal, craftPbr) : vec3(0.0);
    // Opt-in GPU smoke probes: exact SDF silhouette and its isolated, display-mapped material.
    if (CraftDebug == 1) {
        fragColor = vec4(hitCraft ? vec3(1.0) : vec3(0.0), 1.0);
        return;
    }
    if (CraftDebug == 2) {
        fragColor = vec4(pow(filmic(craftCol * .72), vec3(1.0 / 2.2)), 1.0);
        return;
    }

    // 2. Pre-warp undisturbed ray effects: particles and lightning on UNBENT rd!
    vec3 nacelleCol = nacelleParticles(ro, rd, hit);
    vec3 boltCol = lightning(ro, rd, hit);

    // 3. In-place warp space ray modification (Alcubierre metric lens + aberration)
    float metricActive = clamp(WarpActive + smoothstep(1.15, 2.65, WarpStretch) * 0.65 + pow(WarpCharge, 3.5) * 0.08, 0.0, 1.35);
    // A ship hit was measured on the optical ray; its foreground fog must follow that same ray.
    if (metricActive > 0.005 && !hitCraft) {
        vec3 dCraft = ro - CraftPosition;
        float tClosest = max(-dot(dCraft, rd), 0.0);
        vec3 pClosest = ro + rd * tClosest - CraftPosition;
        vec3 bubbleScale = vec3(0.95, 0.65, 1.30 * max(WarpStretch, 1.0)) * ShipScale;
        vec3 pMetric = vec3(dot(pClosest, CraftRight) / bubbleScale.x,
                            dot(pClosest, CraftUp) / bubbleScale.y,
                            dot(pClosest, CraftForward) / bubbleScale.z);
        float bubbleMetric = length(pMetric);
        float outerMask = smoothstep(0.65, 1.55, bubbleMetric);

        // Gravitational lens deflection at the bubble boundary (soft, non-caustic)
        if (bubbleMetric > 0.50 && bubbleMetric < 3.2) {
            float lensWave = exp(-pow((bubbleMetric - 1.15) / 0.78, 2.0));
            vec3 lensCenterDir = normalize(CraftPosition - (ro + rd * tClosest));
            rd = normalize(rd + lensCenterDir * (lensWave * metricActive * 0.065));
        }

        // Relativistic aberration outside the bubble
        float cosTheta = clamp(dot(rd, CraftForward), -0.9999, 0.9999);
        float beta = clamp(0.11 * WarpActive + 0.08 * smoothstep(1.35, 2.75, WarpStretch), 0.0, 0.14);
        if (beta > 0.001) {
            float cosPrime = clamp((cosTheta - beta) / max(1.0 - beta * cosTheta, 0.05), -0.9999, 0.9999);
            float sinPrime = sqrt(max(1.0 - cosPrime * cosPrime, 0.0));
            vec3 ortho = rd - CraftForward * cosTheta;
            float oLen = length(ortho);
            vec3 orthoDir = oLen > 1e-5 ? ortho / oLen : CraftRight;
            vec3 aberratedRd = normalize(CraftForward * cosPrime + orthoDir * sinPrime);
            rd = normalize(mix(rd, aberratedRd, outerMask));
        }
    }

    vec3 signs = mix(vec3(-1.0), vec3(1.0), greaterThanEqual(rd, vec3(0.0)));
    vec3 invRd = signs / max(abs(rd), vec3(1e-6));

    vec3 background = vec3(.0015, .002, .004) + coreRadiance(ro, rd);

    float b = dot(ro, rd), c = dot(ro, ro) - 484.0;
    float discriminant = b * b - c;
    vec3 color = background;
    vec4 cloud = vec4(0.0, 0.0, 0.0, 1.0);
    if (discriminant > 0.0) {
        float root = sqrt(discriminant);
        float sphereNear = max(0.0, -b - root), sphereFar = min(-b + root, hit);
        vec3 invEllip = vec3(1.0 / 23.65, 1.0 / 13.55, 1.0 / 16.90);
        vec3 roE = ro * invEllip, rdE = rd * invEllip;
        float aE = max(dot(rdE, rdE), 1e-6), bE = dot(roE, rdE), cE = dot(roE, roE) - 1.0;
        float discE = bE * bE - aE * cE;
        if (discE > 0.0) {
            float rootE = sqrt(discE);
            float nearT = max(sphereNear, (-bE - rootE) / aE);
            float farT = min(sphereFar, (-bE + rootE) / aE);
            if (farT > nearT) cloud = volume(ro, rd, invRd, nearT, farT);
        }
        // The volume march stops at the hull, so cloud.rgb is foreground dust.
        // During warp that foreground layer is dense enough to wash out the ship;
        // keep a readable veil over the hull while preserving the full medium elsewhere.
        float hullDustScale = hitCraft && (WarpActive > .05 || WarpStretch > 1.05) ? .34 : 1.0;
        color = cloud.rgb * hullDustScale + cloud.a * (hitCraft ? craftCol : background);
        color += boltCol + starlight(ro, rd, hit) * max(cloud.a, .08);
        color += warpParallaxStars(ro, rd, hit) * (hitCraft ? 0.0 : max(cloud.a, 0.14));
        color += nearDust(ro, rd, hit);
    } else if (hitCraft) {
        color = craftCol;
    } else {
        color += boltCol;
    }

    // Nacelle wake particles, localized engine plume and warp flash
    color += nacelleCol;
    color += warpEnginePlume(ro, rd, hit);
    color += warpFlashEffect(ro, rd, hitCraft);

    fragColor = vec4(pow(filmic(color * .72), vec3(1.0 / 2.2)), 1.0);
}
