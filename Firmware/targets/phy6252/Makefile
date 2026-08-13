# Test-DPLS target for PHY6252 / PB-03F using the GCC support shipped with
# the pinned PHY62XX SDK 3.1.2.

CROSS_COMPILE ?= arm-none-eabi-
CC := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
OBJDUMP := $(CROSS_COMPILE)objdump
SIZE := $(CROSS_COMPILE)size

OUT_DIR := out
NAME := test-dpls
ELF := $(OUT_DIR)/$(NAME).elf
HEX := $(OUT_DIR)/$(NAME).hex
BIN := $(OUT_DIR)/$(NAME).bin
MAP := $(OUT_DIR)/$(NAME).map
LST := $(OUT_DIR)/$(NAME).lst

ROOT := ../../sdk/PHY62XX_SDK_3.1.2
PROJ_ROOT := $(ROOT)/example/ble_peripheral/simpleBlePeripheral
PRODUCT_ROOT := ../..

# Product paths come first so our BLE task/header replace the SDK example.
VPATH := source
VPATH += $(PRODUCT_ROOT)/src
VPATH += $(PRODUCT_ROOT)/phy6252
VPATH += $(ROOT)/components/libraries/tinycrypt-0.2.8/lib/source
VPATH += $(ROOT)/components/driver/adc
VPATH += $(PROJ_ROOT)
VPATH += $(PROJ_ROOT)/source

include $(ROOT)/components/gcc/components.cflags
include $(ROOT)/components/gcc/components.mk

# Match the product configuration previously used by the AC6 target.
DEF := $(filter-out -DCFG_SLEEP_MODE=PWR_MODE_NO_SLEEP -DDEF_GAPBOND_MGR_ENABLE=0 -DUSE_FS=0 -DDEBUG_INFO=1,$(DEF))
DEF += -DCFG_SLEEP_MODE=PWR_MODE_SLEEP
DEF += -DCFG_HCLK_DYNAMIC_CHANGE=0
DEF += -DDEF_GAPBOND_MGR_ENABLE=1
DEF += -DUSE_FS=1
DEF += -DDEBUG_INFO=0
DEF += -DMAX_NUM_LL_CONN=1
DEF += -DGATT_MAX_NUM_CONN=MAX_NUM_LL_CONN+1
DEF += -DDPLS_EVENT_CAPACITY=200

INC += -Isource
INC += -I$(PRODUCT_ROOT)/include
INC += -I$(PRODUCT_ROOT)/phy6252
INC += -I$(PROJ_ROOT)
INC += -I$(PROJ_ROOT)/source
INC += -I$(ROOT)/components/libraries/tinycrypt-0.2.8/lib/include

SRC_RAW := main.c
SRC_RAW += OSAL_SimpleBLEPeripheral.c
SRC_RAW += SimpleBLEPeripheral_Main.c
SRC_RAW += dplsBLEPeripheral.c
SRC_RAW += dpls_protocol.c
SRC_RAW += dpls_server.c
SRC_RAW += dpls_led.c
SRC_RAW += dpls_calib.c
SRC_RAW += dpls_gatt_service.c
SRC_RAW += dpls_phy6252_app.c
SRC_RAW += dpls_ble_identity.c
SRC_RAW += hmac.c
SRC_RAW += sha256.c
SRC_RAW += utils.c
SRC_RAW += adc.c
SRC_RAW += $(CSRCS)

STARTUP_RAW := phy6222_start.s

CFLAGS += -std=c99
CFLAGS += -Os
CFLAGS += -g3
CFLAGS += --specs=nosys.specs
CFLAGS += -mcpu=cortex-m0
CFLAGS += -mthumb
CFLAGS += -ffunction-sections
CFLAGS += -fdata-sections
CFLAGS += -funsigned-char
CFLAGS += -fshort-enums
CFLAGS += -fshort-wchar
CFLAGS += -Wall

LFLAGS += -Wl,--gc-sections
LFLAGS += -Wl,-Map=$(MAP)
LFLAGS += $(ROOT)/misc/bb_rom_sym_m0.gdbsym
LFLAGS += $(LIBS_PATH)
LFLAGS += $(LIBS)

LD_SCRIPT := test-dpls.ld

OBJ := $(notdir $(SRC_RAW:%.c=%.o))
STARTUP_OBJ := $(notdir $(STARTUP_RAW:%.s=%.o))

.PHONY: all clean
all: $(ELF) $(HEX) $(BIN) $(LST)

$(OUT_DIR):
	mkdir -p $@

$(ELF): $(OBJ) $(STARTUP_OBJ) $(LD_SCRIPT) | $(OUT_DIR)
	$(CC) -o $@ $(CFLAGS) $(OBJ) $(STARTUP_OBJ) $(LFLAGS) -T$(LD_SCRIPT)
	$(SIZE) $@

$(HEX): $(ELF)
	$(OBJCOPY) -O ihex $< $@

$(BIN): $(ELF)
	$(OBJCOPY) -O binary -S $< $@

$(LST): $(ELF)
	$(OBJDUMP) -d -S $< > $@

%.o: %.s
	$(CC) -c $(CFLAGS) $< -o $@

%.o: %.c
	$(CC) -c $(CFLAGS) $(DEF) $(INC) $< -o $@

clean:
	rm -f $(OBJ) $(STARTUP_OBJ)
	rm -rf $(OUT_DIR)
