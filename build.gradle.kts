plugins {
    id("com.github.ElytraServers.elytra-conventions") version "v1.1.1"
    id("com.gtnewhorizons.gtnhconvention")
}

// 原生库默认用 native/<platform>/ 下预分发的二进制（随 jar 发布）；本机重编用 -PysmuNative=build（要求 cargo）。
apply(from = "gradle/native.gradle")
