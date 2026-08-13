# Test-DPLS target for PHY6252/PB-03F using GCC support from SDK 3.1.2.

SDK := ../../sdk/PHY62XX_SDK_3.1.2
ROOT := $(SDK)
FIRMWARE := ../..
BUILD := build

CC := arm-none-eabi-gcc
OBJCOPY := arm-none-eabi-objcopy
SIZE := arm-none-eabi-size

ELF := $(BUILD)/test-dpls.elf
HEX := $(BUILD)/test-dpls.hex
MAP := $(BUILD)/test-dpls.map
LD_SCRIPT := $(SDK)/example/ble_peripheral/simpleBlePeripheral/gcc/sbp.ld

include $(SDK)/components/gcc/components.cflags
DEF += -UCFG_SLEEP_MODE -DCFG_SLEEP_MODE=PWR_MODE_SLEEP
DEF += -UDEF_GAPBOND_MGR_ENABLE -DDEF_GAPBOND_MGR_ENABLE=1
DEF += -UUSE_FS -DUSE_FS=1
DEF += -DCFG_HCLK_DYNAMIC_CHANGE=0
DEF += -DGATT_MAX_NUM_CONN=MAX_NUM_LL_CONN+1
DEF += -DDPLS_EVENT_CAPACITY=200

INC += -I$(FIRMWARE)/include
INC += -I$(FIRMWARE)/phy6252
INC += -I$(SDK)/components/libraries/tinycrypt-0.2.8/lib/include
INC += -Isource

VPATH := source
VPATH += $(SDK)/example/ble_peripheral/simpleBlePeripheral
VPATH += $(SDK)/example/ble_peripheral/simpleBlePeripheral/source
VPATH += $(SDK)/components/gcc/CMSIS/device/phyplus
VPATH += $(SDK)/components/driver/uart $(SDK)/components/driver/clock
VPATH += $(SDK)/components/driver/gpio $(SDK)/components/driver/adc
VPATH += $(SDK)/components/driver/timer $(SDK)/components/driver/watchdog
VPATH += $(SDK)/components/driver/pwrmgr $(SDK)/components/driver/log
VPATH += $(SDK)/components/driver/key $(SDK)/components/driver/pwm
VPATH += $(SDK)/components/osal/snv $(SDK)/components/libraries/fs
VPATH += $(SDK)/components/driver/flash $(SDK)/components/driver/led_light
VPATH += $(SDK)/components/profiles/Roles $(SDK)/components/profiles/GATT
VPATH += $(SDK)/misc $(FIRMWARE)/src $(FIRMWARE)/phy6252
VPATH += $(SDK)/components/libraries/tinycrypt-0.2.8/lib/source

SRC := main.c phy6222_cstart.c phy6222_vectors.c \
	uart.c clock.c gpio.c adc.c timer.c watchdog.c pwrmgr.c my_printf.c \
	key.c pwm.c osal_snv.c fs.c flash.c led_light.c \
	gap.c gapbondmgr.c gapgattserver.c peripheral.c gattservapp.c jump_table.c \
	OSAL_SimpleBLEPeripheral.c dplsBLEPeripheral.c SimpleBLEPeripheral_Main.c \
	dpls_protocol.c dpls_server.c dpls_led.c dpls_calib.c \
	dpls_gatt_service.c dpls_phy6252_app.c dpls_ble_identity.c \
	hmac.c sha256.c utils.c

STARTUP := phy6222_start.s
OBJ := $(addprefix $(BUILD)/,$(SRC:.c=.o))
STARTUP_OBJ := $(addprefix $(BUILD)/,$(STARTUP:.s=.o))
DEP := $(OBJ:.o=.d)

CFLAGS := -std=c99 -Os -g3 --specs=nosys.specs -mcpu=cortex-m0 -mthumb \
	-funsigned-char -fshort-enums -fshort-wchar -ffunction-sections -fdata-sections -Wall
LDFLAGS := -Wl,--gc-sections -Wl,-Map=$(MAP) $(SDK)/misc/bb_rom_sym_m0.gdbsym \
	-L$(SDK)/lib -lc -lm -lphy6222_rf -lphy6222_sec_boot -lphy6222_host

.PHONY: all clean
all: $(HEX)

$(HEX): $(ELF)
	$(OBJCOPY) -O ihex $< $@
	$(SIZE) $<

$(ELF): $(OBJ) $(STARTUP_OBJ) $(LD_SCRIPT)
	$(CC) $(CFLAGS) $(OBJ) $(STARTUP_OBJ) $(LDFLAGS) -T$(LD_SCRIPT) -o $@

$(BUILD)/%.o: %.c | $(BUILD)
	$(CC) $(CFLAGS) $(DEF) $(INC) -MMD -MP -c $< -o $@

$(BUILD)/%.o: %.s | $(BUILD)
	$(CC) $(CFLAGS) -c $< -o $@

$(BUILD):
	mkdir -p $@

clean:
	rm -rf $(BUILD)

-include $(DEP)
