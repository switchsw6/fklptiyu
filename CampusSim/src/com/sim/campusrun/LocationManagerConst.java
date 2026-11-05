package com.sim.campusrun;

/** 常量集中处，避免各 hook 文件重复 import */
final class LocationManagerConst {
    static final String GPS = "gps";
    static final String NETWORK = "network";
    static final String FUSED = "fused";

    private LocationManagerConst() { }
}
