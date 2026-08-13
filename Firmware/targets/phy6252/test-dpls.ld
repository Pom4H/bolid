MEMORY
{
  jumptbl (rwx) : ORIGIN = 0x1fff0000, LENGTH = 1K
  gcfgtbl (rwx) : ORIGIN = 0x1fff0400, LENGTH = 1K
  flash (rx) : ORIGIN = 0x11020000, LENGTH = 0x1c000
  sram (rwx) : ORIGIN = 0x1fff1880, LENGTH = 59264
}

OUTPUT_ARCH(arm)
EXTERN(__Vectors)
ENTRY(__start)

SECTIONS
{
  .jump_table : { KEEP(*(jump_table_mem_area)) } > jumptbl
  .global_config (NOLOAD) : { KEEP(*(global_config_area)) } > gcfgtbl

  .textentry :
  {
    _stext = ABSOLUTE(.);
    KEEP(*(.isr_vector))
    *phy6222_start.o(.text)
  } > flash

  .init_section :
  {
    _sinit = ABSOLUTE(.);
    KEEP(*(.init_array .init_array.*))
    _einit = ABSOLUTE(.);
  } > flash

  .ARM.extab : { *(.ARM.extab*) } > flash
  __exidx_start = ABSOLUTE(.);
  .ARM.exidx : { *(.ARM.exidx*) } > flash
  __exidx_end = ABSOLUTE(.);

  ._eronlystore : { _eronly = ABSOLUTE(.); } > flash

  .data :
  {
    _sdata = ABSOLUTE(.);
    _stextram = ABSOLUTE(.);
    *.o(_section_standby_code_)
    *.o(_section_standby_text_)
    *.o(_section_sram_code_)
    *flash.o(.text .text.*)
    *patch.o(.text .text.*)
    *clock.o(.text.hal_xtal16m_cap_Set)
    *rf_phy_driver.o(.text .text.*)
    *libphy6222_host.a:l2cap_util.o(.text.L2CAP_Fragment_SendDataPkt)
    *libphy6222_host.a:l2cap_util.o(.text.l2capSegmentBuffToLinkLayer)
    *libphy6222_host.a:l2cap_util.o(.text.l2capPocessFragmentTxData)
    _etextram = ABSOLUTE(.);
    *(.data .data.*)
    *(.gnu.linkonce.d.*)
    CONSTRUCTORS
    . = ALIGN(4);
    _edata = ABSOLUTE(.);
  } > sram AT > flash

  .bss (NOLOAD) :
  {
    _sbss = ABSOLUTE(.);
    *(.bss .bss.*)
    *(.gnu.linkonce.b.*)
    *(COMMON)
    . = ALIGN(4);
    __heap_start__ = .;
    end = __heap_start__;
    _end = end;
    __end = end;
    _ebss = ABSOLUTE(.);
  } > sram

  .int_stack (NOLOAD) :
  {
    . = ALIGN(8);
    KEEP(*(int_stack))
    . = ALIGN(8);
    _stack_top = ABSOLUTE(.);
  } > sram

  .common_text :
  {
    *(.text .text.*)
    *(.rodata .rodata.*)
    *(.fixup)
    *(.gnu.warning)
    *(.gnu.linkonce.t.*)
    *(.glue_7)
    *(.glue_7t)
    *(.got)
    *(.gcc_except_table)
    *(.gnu.linkonce.r.*)
    *(_section_xip_code_)
    *(_func_xip_code_.*)
    _etext = ABSOLUTE(.);
  } > flash
}
