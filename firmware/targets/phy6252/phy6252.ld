ENTRY(Reset_Handler)

MEMORY
{
    JT   (rwx) : ORIGIN = 0x1fff0000, LENGTH = 0x400
    GC   (rwx) : ORIGIN = 0x1fff0400, LENGTH = 0x400
    MAIN (rwx) : ORIGIN = 0x1fff1838, LENGTH = 0x77c8
    RAM2 (rwx) : ORIGIN = 0x1fffc000, LENGTH = 0x4000
    /* 0x1103C000..0x1103EFFF is SNV; 0x1103F000..0x1103FFFF is factory identity. */
    XIP  (rx)  : ORIGIN = 0x11020000, LENGTH = 0x1c000
}

SECTIONS
{
    .jump_table ORIGIN(JT) : { KEEP(*(jump_table_mem_area)) } > JT
    .global_config ORIGIN(GC) : { KEEP(*(global_config_area)) } > GC

    .xip ORIGIN(XIP) :
    {
        *ble_host.lib:*(i.* .text* .rodata*)
        *gap*.o(.text* .rodata*)
        *gatt*.o(.text* .rodata*)
        *peripheral.o(.text* .rodata*)
        *SimpleBLEPeripheral*.o(.text* .rodata*)
        *dplsBLEPeripheral.o(.text* .rodata*)
        *dpls*.o(.text* .rodata*)
        *hmac.o(.text* .rodata*)
        *sha256.o(.text* .rodata*)
        *utils.o(.text* .rodata*)
        *my_printf.o(.text* .rodata*)
        *gpio.o(.text* .rodata*)
        *adc.o(.text* .rodata*)
        *libgcc.a:*(.text* .rodata*)
        *(_section_xip_code_)
        *(_func_xip_code_.*)
    } > XIP

    .ram2 ORIGIN(RAM2) :
    {
        __ram2_start__ = .;
        *rf.lib:rf_phy_driver.o(i.rf_phy_dtm* i.rf_phy_direct_*)
        . = ALIGN(4);
        __ram2_end__ = .;
    } > RAM2 AT> XIP
    __ram2_load_start__ = LOADADDR(.ram2);

    .vectors ORIGIN(MAIN) : { KEEP(*(.vectors)) } > MAIN
    .text :
    {
        *(.text.Reset_Handler)
        *(_section_standby_code_)
        *(_section_standby_text_)
        *(_section_sram_code_)
        *(.text*)
        *rf.lib:*(i.* .text* .rodata*)
        *(.rodata*)
        *(.ARM.extab*)
        *(.ARM.exidx*)
        . = ALIGN(4);
    } > MAIN
    .data :
    {
        . = ALIGN(4);
        __data_start__ = .;
        *(.data*)
        *(data*)
        . = ALIGN(4);
        __data_end__ = .;
    } > MAIN
    .bss (NOLOAD) :
    {
        . = ALIGN(4);
        __bss_start__ = .;
        *(.bss*)
        *(COMMON)
        . = ALIGN(4);
        __bss_end__ = .;
    } > MAIN

    __StackTop = ORIGIN(MAIN) + LENGTH(MAIN);
    __StackLimit = __StackTop - 0x400;
    __initial_sp = __StackTop;
    __stack_limit = __StackLimit;
    /DISCARD/ : { *(.comment) *(.note*) }
}
ASSERT(SIZEOF(.jump_table) <= LENGTH(JT), "jump table overflow")
ASSERT(SIZEOF(.global_config) <= LENGTH(GC), "global config overflow")
ASSERT(SIZEOF(.ram2) <= LENGTH(RAM2), "SRAM2 overflow")
ASSERT(__bss_end__ <= __StackLimit, "MAIN SRAM overflow")
