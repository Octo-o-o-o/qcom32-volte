# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Free for personal & 100% noncommercial community ROMs; commercial use (paid ROMs or pre-flashed phone sales) requires a paid license from OctoLab (octoooo@octoooo.com).
# Inherit from your 32-bit Qualcomm LineageOS 18.1 device.mk (e.g. device/blackberry/wseries/device.mk).

PRODUCT_PACKAGES += \
    Qcom32VolteIms \
    libimsa_probe.so

PRODUCT_PROPERTY_OVERRIDES += \
    persist.dbg.volte_avail_ovr=1
