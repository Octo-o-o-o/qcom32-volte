# SPDX-License-Identifier: Apache-2.0
# Inherit from your 32-bit Qualcomm LineageOS 18.1 device.mk (e.g. device/blackberry/wseries/device.mk).

PRODUCT_PACKAGES += \
    Qcom32VolteIms \
    libimsa_probe.so

PRODUCT_PROPERTY_OVERRIDES += \
    persist.dbg.volte_avail_ovr=1
