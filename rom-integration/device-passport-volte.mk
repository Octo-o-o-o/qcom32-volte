# SPDX-License-Identifier: Apache-2.0
# Inherit from device/blackberry/wseries/device.mk when building LineageOS 18.1 from source.

PRODUCT_PACKAGES += \
    PassportVolteIms \
    libimsa_probe.so

PRODUCT_PROPERTY_OVERRIDES += \
    persist.dbg.volte_avail_ovr=1
