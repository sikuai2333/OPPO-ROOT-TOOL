/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Device profiles offered by the privacy centre for whole-device model spoofing.
 *
 * <p>Every profile is a real, retail device built on the same SoC family as this
 * phone (Qualcomm SM8250, "kona"), so the ABI list, screen density class and the
 * Play device list all stay plausible. The fields come in pairs that have to
 * agree: the fingerprint is exactly
 * {@code brand/product/device:release/buildId/incremental:type/tags} and is
 * built from the neighbouring fields by {@link Profile#fingerprint()}, so a
 * typo in one field cannot produce a fingerprint that contradicts the model.
 *
 * <p>Names and values are mirrored in SystemServer (property suffixes) and
 * Build ({@code ro.hima.spoof.*}); keep the three in step.
 */
public final class DeviceProfiles {

    private DeviceProfiles() {
    }

    /** One selectable device identity. */
    public static final class Profile {
        /** Stable id stored in the settings; never translate it. */
        public final String id;
        /** Name shown in the picker. */
        public final String displayName;
        public final String brand;
        /** ro.product.name, e.g. {@code OnePlus8Pro_EEA}. */
        public final String product;
        public final String device;
        public final String model;
        public final String manufacturer;
        /** ro.hardware; shown for context, never applied. */
        public final String hardware;
        /** ro.build.id, the framework build this ROM image was made from. */
        public final String buildId;
        /** ro.build.version.incremental, the vendor build number. */
        public final String incremental;
        /** ro.build.version.release, the Android version that shipped it. */
        public final String release;
        /** ro.build.version.security_patch. */
        public final String securityPatch;

        Profile(String id, String displayName, String brand, String product, String device,
                String model, String manufacturer, String hardware, String buildId,
                String incremental, String release, String securityPatch) {
            this.id = id;
            this.displayName = displayName;
            this.brand = brand;
            this.product = product;
            this.device = device;
            this.model = model;
            this.manufacturer = manufacturer;
            this.hardware = hardware;
            this.buildId = buildId;
            this.incremental = incremental;
            this.release = release;
            this.securityPatch = securityPatch;
        }

        /** The canonical build fingerprint; all other fields have to agree with it. */
        public String fingerprint() {
            return brand + '/' + product + '/' + device + ':' + release + '/' + buildId + '/'
                    + incremental + ":user/release-keys";
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * Profiles, all SM8250. The OnePlus 8 / 8 Pro fingerprints (IN2013, IN2015,
     * IN2023) are the stock ones listed in the long-standing MagiskHidePropsConf
     * fingerprint database, so they are a verified coherent
     * brand/product/device:release/build id/incremental set. They differ only in
     * variant, which is exactly what a user rotating identities wants.
     */
    public static final List<Profile> ALL = Collections.unmodifiableList(Arrays.asList(
            // OnePlus 8 Pro EEA.
            new Profile("oneplus8pro", "OnePlus 8 Pro (IN2023)",
                    "OnePlus", "OnePlus8Pro_EEA", "OnePlus8Pro", "IN2023", "OnePlus", "qcom",
                    "RP1A.201005.001", "2108261432", "11", "2021-08-01"),
            // OnePlus 8 EEA.
            new Profile("oneplus8", "OnePlus 8 (IN2013)",
                    "OnePlus", "OnePlus8_EEA", "OnePlus8", "IN2013", "OnePlus", "qcom",
                    "RP1A.201005.001", "2110091918", "11", "2021-10-01"),
            // OnePlus 8 global.
            new Profile("oneplus8_g", "OnePlus 8 (IN2015)",
                    "OnePlus", "OnePlus8", "OnePlus8", "IN2015", "OnePlus", "qcom",
                    "RP1A.201005.001", "2110102308", "11", "2021-10-01"),
            // OnePlus 8 Pro China.
            new Profile("oneplus8pro_cn", "OnePlus 8 Pro (IN2020)",
                    "OnePlus", "OnePlus8Pro_CH", "OnePlus8Pro", "IN2020", "OnePlus", "qcom",
                    "RP1A.201005.001", "2108261632", "11", "2021-08-01")));

    /** Returns the profile with {@code id}, or {@code null} if it is not offered. */
    public static Profile byId(String id) {
        if (id == null) {
            return null;
        }
        for (Profile p : ALL) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }
}
