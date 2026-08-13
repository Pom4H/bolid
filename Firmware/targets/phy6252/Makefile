# PHY6252 Test-DPLS — GNU Arm (gcc-arm-none-eabi).
# Production image is still the Keil/AC6 build; this target is the same sources
# and the same memory map for people without Arm Compiler 6.
#
#   make -C Firmware/targets/phy6252
#   tools/build_firmware.sh gcc tmp/test-dpls.hex

SHELL := /bin/bash
CC ?= arm-none-eabi-gcc
OBJCOPY ?= arm-none-eabi-objcopy
SIZE ?= arm-none-eabi-size

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
	-ffunction-sections -fdata-sections -fno-common \
	-Wall -Wextra -Wno-unused-parameter -Wno-sign-compare

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

INCLUDES := \
	-Isource \
	-I$(SDK)/example/ble_peripheral/simpleBlePeripheral/source \
	-I$(SDK)/components/inc \
	-I$(SDK)/components/ble/controller \
	-I$(SDK)/components/osal/include \
	-I$(SDK)/components/common \
	-I$(SDK)/components/ble/include \
	-I$(SDK)/components/ble/hci \
	-I$(SDK)/components/ble/host \
	-I$(SDK)/components/profiles/ota_app \
	-I$(SDK)/components/profiles/DevInfo \
	-I$(SDK)/components/profiles/SimpleProfile \
	-I$(SDK)/components/profiles/Roles \
	-I$(SDK)/components/profiles/GATT \
	-I$(SDK)/components/libraries/crc16 \
	-I$(SDK)/components/driver/watchdog \
	-I$(SDK)/components/driver/clock \
	-I$(SDK)/components/arch/cm0 \
	-I$(SDK)/components/gcc/CMSIS/include \
	-I$(SDK)/components/driver/pwrmgr \
	-I$(SDK)/components/driver/uart \
	-I$(SDK)/components/driver/gpio \
	-I$(SDK)/components/driver/adc \
	-I$(SDK)/components/driver/timer \
	-I$(SDK)/misc \
	-I$(SDK)/components/driver/log \
	-I$(SDK)/components/libraries/cliface \
	-I$(SDK)/components/driver/key \
	-I$(SDK)/components/driver/pwm \
	-I$(SDK)/components/driver/flash \
	-I$(SDK)/components/libraries/fs \
	-I$(SDK)/components/driver/led_light \
	-I$(SDK)/components/libraries/tinycrypt-0.2.8/lib/include \
	-I$(FW)/include \
	-I$(FW)/phy6252

C_SOURCES := \
	$(SDK)/example/ble_peripheral/simpleBlePeripheral/main.c \
	$(SDK)/components/driver/uart/uart.c \
	$(SDK)/components/driver/clock/clock.c \
	$(SDK)/components/driver/gpio/gpio.c \
	$(SDK)/components/driver/adc/adc.c \
	$(SDK)/components/driver/timer/timer.c \
	$(SDK)/components/driver/watchdog/watchdog.c \
	$(SDK)/components/driver/pwrmgr/pwrmgr.c \
	$(SDK)/components/driver/log/my_printf.c \
	$(SDK)/components/driver/key/key.c \
	$(SDK)/components/driver/pwm/pwm.c \
	$(SDK)/components/osal/snv/osal_snv.c \
	$(SDK)/components/libraries/fs/fs.c \
	$(SDK)/components/driver/flash/flash.c \
	$(SDK)/components/driver/led_light/led_light.c \
	$(SDK)/components/profiles/Roles/gap.c \
	$(SDK)/components/profiles/Roles/gapbondmgr.c \
	$(SDK)/components/profiles/Roles/gapgattserver.c \
	$(SDK)/components/profiles/Roles/peripheral.c \
	$(SDK)/components/profiles/GATT/gattservapp.c \
	$(SDK)/misc/jump_table.c \
	$(SDK)/example/ble_peripheral/simpleBlePeripheral/source/OSAL_SimpleBLEPeripheral.c \
	source/dplsBLEPeripheral.c \
	$(SDK)/example/ble_peripheral/simpleBlePeripheral/source/SimpleBLEPeripheral_Main.c \
	$(FW)/src/dpls_protocol.c \
	$(FW)/src/dpls_server.c \
	$(FW)/src/dpls_led.c \
	$(FW)/src/dpls_calib.c \
	$(FW)/phy6252/dpls_gatt_service.c \
	$(FW)/phy6252/dpls_phy6252_app.c \
	$(FW)/phy6252/dpls_ble_identity.c \
	$(SDK)/components/libraries/tinycrypt-0.2.8/lib/source/hmac.c \
	$(SDK)/components/libraries/tinycrypt-0.2.8/lib/source/sha256.c \
	$(SDK)/components/libraries/tinycrypt-0.2.8/lib/source/utils.c \
	system_gcc.c

OBJECTS := $(addprefix $(BUILD)/,$(notdir $(C_SOURCES:.c=.o))) $(BUILD)/startup_phy6252.o
DEPS := $(OBJECTS:.o=.d)
RF_LIB := $(SDK)/lib/rf.lib
BLE_LIB := $(SDK)/lib/ble_host.lib

.PHONY: all size clean
all: $(HEX)

$(BUILD):
	@mkdir -p $@

$(ROM_LD): $(ROM_SYM) | $(BUILD)
	@awk '$$1 ~ /^0x[0-9A-Fa-f]+$$/ && $$3 ~ /^[A-Za-z_.$$][A-Za-z0-9_.$$]*$$/ { print "PROVIDE(" $$3 " = " $$1 ");" }' $< > $@

define COMPILE_C
$(BUILD)/$(notdir $(1:.c=.o)): $(1) | $(BUILD)
	$(CC) $(CFLAGS) $(DEFS) $(INCLUDES) -MMD -MP -c $$< -o $$@
endef
$(foreach src,$(C_SOURCES),$(eval $(call COMPILE_C,$(src))))

$(BUILD)/startup_phy6252.o: startup_phy6252.S | $(BUILD)
	$(CC) $(ARCH) -x assembler-with-cpp -MMD -MP -c $< -o $@

$(ELF): $(OBJECTS) $(ROM_LD) $(LDSCRIPT) $(RF_LIB) $(BLE_LIB)
	$(CC) $(ARCH) -nostartfiles -nostdlib \
		-Wl,-Map,$(MAP),--cref,--gc-sections,--undefined=jump_table_base,--undefined=global_config \
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
