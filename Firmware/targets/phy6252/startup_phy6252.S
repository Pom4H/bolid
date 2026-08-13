/* Minimal Cortex-M0 startup for the PHY6252 Test-DPLS target.
 * Vector layout matches ARM::Device:Startup used by the previous AC6 build.
 */
.syntax unified
.cpu cortex-m0
.thumb

.section .vectors,"a",%progbits
.align 2
.global __Vectors
.global __Vectors_End
__Vectors:
    .word __initial_sp
    .word Reset_Handler
    .word NMI_Handler
    .word HardFault_Handler
    .word 0, 0, 0, 0, 0, 0, 0
    .word SVC_Handler
    .word 0, 0
    .word PendSV_Handler
    .word SysTick_Handler
    .word Interrupt0_Handler
    .word Interrupt1_Handler
    .word Interrupt2_Handler
    .word Interrupt3_Handler
    .word Interrupt4_Handler
    .word Interrupt5_Handler
    .word Interrupt6_Handler
    .word Interrupt7_Handler
    .word Interrupt8_Handler
    .word Interrupt9_Handler
    .space (22 * 4)
__Vectors_End:
.global __Vectors_Size
.equ __Vectors_Size, __Vectors_End - __Vectors

.section .text.Reset_Handler,"ax",%progbits
.align 2
.thumb_func
.global Reset_Handler
.type Reset_Handler, %function
Reset_Handler:
    bl SystemInit

    /* Copy the small RF block that must execute from SRAM2. */
    ldr r0, =__ram2_load_start__
    ldr r1, =__ram2_start__
    ldr r2, =__ram2_end__
1:
    cmp r1, r2
    bcs 2f
    ldr r3, [r0]
    str r3, [r1]
    adds r0, #4
    adds r1, #4
    b 1b
2:
    /* Zero normal BSS. */
    ldr r0, =__bss_start__
    ldr r1, =__bss_end__
    movs r2, #0
3:
    cmp r0, r1
    bcs 4f
    str r2, [r0]
    adds r0, #4
    b 3b
4:
    bl main
5:
    b 5b
.size Reset_Handler, . - Reset_Handler

.macro LOCAL_DEFAULT name
    .section .text.\name,"ax",%progbits
    .weak \name
    .thumb_func
\name:
    b .
.endm

/* NMI, HardFault and PendSV intentionally come from bb_rom_sym_m0.txt,
 * matching the previous image. The remaining weak defaults are local. */
LOCAL_DEFAULT SVC_Handler
LOCAL_DEFAULT SysTick_Handler
LOCAL_DEFAULT Interrupt0_Handler
LOCAL_DEFAULT Interrupt1_Handler
LOCAL_DEFAULT Interrupt2_Handler
LOCAL_DEFAULT Interrupt3_Handler
LOCAL_DEFAULT Interrupt4_Handler
LOCAL_DEFAULT Interrupt5_Handler
LOCAL_DEFAULT Interrupt6_Handler
LOCAL_DEFAULT Interrupt7_Handler
LOCAL_DEFAULT Interrupt8_Handler
LOCAL_DEFAULT Interrupt9_Handler
