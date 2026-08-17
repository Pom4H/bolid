# PHY6252 Test-DPLS — GNU Arm (gcc-arm-none-eabi).
# Production image is still the Keil/AC6 build; this target is the same sources
# and the same memory map for people without Arm Compiler 6.
#
#   make -C firmware/targets/phy6252
#   tools/build_firmware.sh gcc tmp/test-dpls.hex

SHELL := /bin/bash
CROSS ?= arm-none-eabi-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
SIZE := $(CROSS)size

FW := ../..
SDK := $(FW)/sdk/PHY62XX_SDK_3.1.2
BUILD ?= build
ELF := $(BUILD)/test-dpls.elf
MAP := $(BUILD)/test-dpls.map
HEX ?= $(BUILD)/test-dpls.hex
ROM_SYM := $(SDK)/misc/bb_rom_sym_m0.txt
ROM_LD := $(BUILD)/rom_symbols.ld
LDSCRIPT := phy6252.ld

ARCH := -mcpu=cortex-m0 -mthumb
CFLAGS := $(ARCH) -std=c99 -Os -g3 \
	-funsigned-char -fshort-enums -fshort-wchar \
	-ffunction-sections -fdata-sections -fno-common
VENDOR_CFLAGS := $(CFLAGS) -w
APP_CFLAGS := $(CFLAGS) -Wall -Wextra -Werror -Wno-unused-parameter -Wno-sign-compare

DEFS := \
	-DCFG_CP \
	-DOSAL_CBTIMER_NUM_TASKS=1 \
	-DMTU_SIZE=247 \
	-DHOST_CONFIG=4 \
	-DHCI_TL_NONE=1 \
	-DENABLE_LOG_ROM_=0 \
	-D_BUILD_FOR_DTM_=0 \
	-DDEBUG_INFO=1 \
	-DDBG_ROM_MAIN=0 \
	-DAPP_CFG=0 \
	-DOSALMEM_METRICS=0 \
	-DPHY_MCU_TYPE=MCU_BUMBEE_M0 \
	-DCFG_SLEEP_MODE=PWR_MODE_SLEEP \
	-DCFG_SRAM_RETENTION_LOW_CURRENT_LDO_ENABLE \
	-DCFG_HCLK_DYNAMIC_CHANGE=0 \
	-DDEF_GAPBOND_MGR_ENABLE=1 \
	-DUSE_FS=1 \
	-DMAX_NUM_LL_CONN=1 \
	'-DGATT_MAX_NUM_CONN=(MAX_NUM_LL_CONN+1)' \
	-DDPLS_EVENT_CAPACITY=200 \
	-DADV_NCONN_CFG=0x01 -DADV_CONN_CFG=0x02 \
	-DSCAN_CFG=0x04 -DINIT_CFG=0x08 \
	-DBROADCASTER_CFG=0x01 -DOBSERVER_CFG=0x02 \
	-DPERIPHERAL_CFG=0x04 -DCENTRAL_CFG=0x08

# SDK headers are -isystem so unused static prototypes in adc.h (and friends)
# do not fail the app objects under -Werror. Those warnings fire at end of TU,
# after a local diagnostic pragma around the include has already been popped.
APP_INCLUDES := \
	-Isource \
	-I$(FW)/include \
	-I$(FW)/phy6252
SDK_INCLUDES := \
	-isystem $(SDK)/example/ble_peripheral/simpleBlePeripheral/source \
	-isystem $(SDK)/components/inc \
	-isystem $(SDK)/components/ble/controller \
	-isystem $(SDK)/components/osal/include \
	-isystem $(SDK)/components/common \
	-isystem $(SDK)/components/ble/include \
	-isystem $(SDK)/components/ble/hci \
	-isystem $(SDK)/components/ble/host \
	-isystem $(SDK)/components/profiles/ota_app \
	-isystem $(SDK)/components/profiles/DevInfo \
	-isystem $(SDK)/components/profiles/SimpleProfile \
	-isystem $(SDK)/components/profiles/Roles \
	-isystem $(SDK)/components/profiles/GATT \
	-isystem $(SDK)/components/libraries/crc16 \
	-isystem $(SDK)/components/driver/watchdog \
	-isystem $(SDK)/components/driver/clock \
	-isystem $(SDK)/components/arch/cm0 \
	-isystem $(SDK)/components/gcc/CMSIS/include \
	-isystem $(SDK)/components/driver/pwrmgr \
	-isystem $(SDK)/components/driver/uart \
	-isystem $(SDK)/components/driver/gpio \
	-isystem $(SDK)/components/driver/adc \
	-isystem $(SDK)/components/driver/timer \
	-isystem $(SDK)/misc \
	-isystem $(SDK)/components/driver/log \
	-isystem $(SDK)/components/libraries/cliface \
	-isystem $(SDK)/components/driver/key \
	-isystem $(SDK)/components/driver/flash \
	-isystem $(SDK)/components/libraries/fs \
	-isystem $(SDK)/components/libraries/tinycrypt-0.2.8/lib/include
INCLUDES := $(APP_INCLUDES) $(SDK_INCLUDES)

# Keep the target surface to drivers actually owned by this application or
# required by the vendor BLE runtime. key.c/pwm/led_light are example baggage;
# key.h remains because the vendor OSAL sample includes it transitively through
# halPeripheral.h. DPLS owns button polling and LED scene state itself.
SDK_SOURCES := \
	$(SDK)/example/ble_peripheral/simpleBlePeripheral/main.c \
	$(SDK)/components/driver/uart/uart.c \
	$(SDK)/components/driver/clock/clock.c \
	$(SDK)/components/driver/gpio/gpio.c \
	$(SDK)/components/driver/adc/adc.c \
	$(SDK)/components/driver/timer/timer.c \
	$(SDK)/components/driver/watchdog/watchdog.c \
	$(SDK)/components/driver/pwrmgr/pwrmgr.c \
	$(SDK)/components/driver/log/my_printf.c \
	$(SDK)/components/osal/snv/osal_snv.c \
	$(SDK)/components/libraries/fs/fs.c \
	$(SDK)/components/driver/flash/flash.c \
	$(SDK)/components/profiles/Roles/gap.c \
	$(SDK)/components/profiles/Roles/gapbondmgr.c \
	$(SDK)/components/profiles/Roles/gapgattserver.c \
	$(SDK)/components/profiles/Roles/peripheral.c \
	$(SDK)/components/profiles/GATT/gattservapp.c \
	$(SDK)/misc/jump_table.c \
	$(SDK)/example/ble_peripheral/simpleBlePeripheral/source/OSAL_SimpleBLEPeripheral.c \
	$(SDK)/example/ble_peripheral/simpleBlePeripheral/source/SimpleBLEPeripheral_Main.c \
	$(SDK)/components/libraries/tinycrypt-0.2.8/lib/source/hmac.c \
	$(SDK)/components/libraries/tinycrypt-0.2.8/lib/source/sha256.c \
	$(SDK)/components/libraries/tinycrypt-0.2.8/lib/source/utils.c

APP_SOURCES := \
	source/dplsBLEPeripheral.c \
	$(FW)/src/dpls_protocol.c \
	$(FW)/src/dpls_server.c \
	$(FW)/src/dpls_safety.c \
	$(FW)/src/dpls_led.c \
	$(FW)/src/dpls_calib.c \
	$(FW)/phy6252/dpls_gatt_service.c \
	$(FW)/phy6252/dpls_phy6252_app.c \
	$(FW)/phy6252/dpls_ble_identity.c \
	system_gcc.c

OBJECTS := $(addprefix $(BUILD)/,$(notdir $(SDK_SOURCES:.c=.o))) \
	$(addprefix $(BUILD)/,$(notdir $(APP_SOURCES:.c=.o))) \
	$(BUILD)/startup_phy6252.o
DEPS := $(OBJECTS:.o=.d)
RF_LIB_SRC := $(SDK)/lib/rf.lib
BLE_LIB_SRC := $(SDK)/lib/ble_host.lib
RF_LIB := $(BUILD)/rf.lib
BLE_LIB := $(BUILD)/ble_host.lib
FIX_AR := $(FW)/../tools/fix_armcc_archive.py

.PHONY: all size clean
all: $(HEX)

$(BUILD):
	@mkdir -p $@

$(ROM_LD): $(ROM_SYM) | $(BUILD)
	@awk '$$1 ~ /^0x[0-9A-Fa-f]+$$/ && $$3 ~ /^[A-Za-z_.$$][A-Za-z0-9_.$$]*$$/ { print "PROVIDE(" $$3 " = " $$1 ");" }' $< > $@

define COMPILE_SDK
$(BUILD)/$(notdir $(1:.c=.o)): $(1) | $(BUILD)
	$(CC) $(VENDOR_CFLAGS) $(DEFS) $(INCLUDES) -MMD -MP -c $$< -o $$@
endef
$(foreach src,$(SDK_SOURCES),$(eval $(call COMPILE_SDK,$(src))))

define COMPILE_APP
$(BUILD)/$(notdir $(1:.c=.o)): $(1) | $(BUILD)
	$(CC) $(APP_CFLAGS) $(DEFS) $(INCLUDES) -MMD -MP -c $$< -o $$@
endef
$(foreach src,$(APP_SOURCES),$(eval $(call COMPILE_APP,$(src))))

# Vendor adc.h declares static helpers that are defined in adc.c; GCC still
# emits -Wunused-function at end of this TU even with -isystem.
$(BUILD)/dpls_phy6252_app.o: APP_CFLAGS += -Wno-unused-function
# GCC recognizes the byte loop as a memset pattern even with -fno-builtin and
# otherwise lowers memset() back into a call to itself. Disable that transform
# for the freestanding libc shim so the emitted Cortex-M0 code remains a loop.
$(BUILD)/system_gcc.o: APP_CFLAGS += -fno-builtin -fno-tree-loop-distribute-patterns

$(BUILD)/startup_phy6252.o: startup_phy6252.S | $(BUILD)
	$(CC) $(ARCH) -x assembler-with-cpp -MMD -MP -c $< -o $@

$(RF_LIB): $(RF_LIB_SRC) $(FIX_AR) | $(BUILD)
	python3 $(FIX_AR) $< $@

$(BLE_LIB): $(BLE_LIB_SRC) $(FIX_AR) | $(BUILD)
	python3 $(FIX_AR) $< $@

$(ELF): $(OBJECTS) $(ROM_LD) $(LDSCRIPT) $(RF_LIB) $(BLE_LIB)
	$(CC) $(ARCH) -nostartfiles -nostdlib \
		-Wl,-Map,$(MAP),--cref,--gc-sections,--undefined=jump_table_base,--undefined=global_config \
		-Wl,--no-warn-mismatch,--allow-multiple-definition \
		-T $(LDSCRIPT) -T $(ROM_LD) -o $@ $(OBJECTS) \
		-Wl,--start-group $(RF_LIB) $(BLE_LIB) -lgcc -Wl,--end-group
	$(SIZE) $@

$(HEX): $(ELF)
	@mkdir -p $(dir $(HEX))
	$(OBJCOPY) -O ihex $< $@

size: $(ELF)
	$(SIZE) -A $(ELF)

clean:
	rm -rf $(BUILD)

-include $(DEPS)
