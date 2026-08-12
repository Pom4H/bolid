/* Production startup for Test-DPLS on PHY6252.
 *
 * The SDK simpleBlePeripheral/main.c is an example application, not a suitable
 * product entry point: it reserves SRAM2 for DTM helpers and enters RF direct
 * test when P20 is high. P20 is the Test-DPLS +1 ADC input, so that demo hook
 * is both electrically wrong and forces an otherwise-unused SRAM bank to stay
 * retained. Keep only the SDK initialisation that the BLE stack actually needs.
 */
#include "bus_dev.h"
#include "clock.h"
#include "flash.h"
#include "gpio.h"
#include "host_cfg.h"
#include "jump_function.h"
#include "log.h"
#include "pwrmgr.h"
#include "rf_phy_driver.h"
#include "timer.h"
#include "version.h"
#include "watchdog.h"

#define BLE_MAX_ALLOW_CONNECTION       1
#define BLE_MAX_ALLOW_PKT_PER_EVENT_TX 3
#define BLE_MAX_ALLOW_PKT_PER_EVENT_RX 3
#define BLE_PKT_VERSION                BLE_PKT_VERSION_5_1
#define BLE_PKT_BUF_SIZE               (BLE_PKT51_LEN + (sizeof(struct ll_pkt_desc) - 2))
#define BLE_MAX_ALLOW_PER_CONNECTION   BLE_PKT_BUF_SIZE
#define BLE_CONN_BUF_SIZE              (BLE_MAX_ALLOW_CONNECTION * BLE_MAX_ALLOW_PER_CONNECTION)
#define LARGE_HEAP_SIZE                (3u * 1024u)

ALIGN4_U8 g_pConnectionBuffer[BLE_CONN_BUF_SIZE];
llConnState_t pConnContext[BLE_MAX_ALLOW_CONNECTION];
ALIGN4_U8 g_largeHeap[LARGE_HEAP_SIZE];

volatile uint8 g_clk32K_config;
volatile sysclk_t g_spif_clk_config;

extern void init_config(void);
extern int app_main(void);
extern void hal_rom_boot_init(void);

static void low_power_io_init(void)
{
    static const ioinit_cfg_t io_init[] = {
        {GPIO_P02, GPIO_FLOATING},
        {GPIO_P03, GPIO_FLOATING},
        {GPIO_P09, GPIO_PULL_UP},
        {GPIO_P10, GPIO_PULL_UP},
        {GPIO_P11, GPIO_PULL_DOWN},
        {GPIO_P14, GPIO_PULL_DOWN},
        {GPIO_P15, GPIO_FLOATING},
        {GPIO_P16, GPIO_FLOATING},
        {GPIO_P18, GPIO_PULL_DOWN},
        {GPIO_P20, GPIO_FLOATING},
        {GPIO_P00, GPIO_PULL_DOWN},
        {GPIO_P01, GPIO_PULL_DOWN},
        {GPIO_P07, GPIO_PULL_DOWN},
        {GPIO_P17, GPIO_FLOATING},
        {GPIO_P23, GPIO_FLOATING},
        {GPIO_P24, GPIO_FLOATING},
        {GPIO_P25, GPIO_PULL_DOWN},
        {GPIO_P26, GPIO_PULL_DOWN},
        {GPIO_P27, GPIO_PULL_DOWN},
        {GPIO_P31, GPIO_PULL_DOWN},
        {GPIO_P32, GPIO_PULL_DOWN},
        {GPIO_P33, GPIO_PULL_DOWN},
        {GPIO_P34, GPIO_PULL_DOWN},
    };
    uint8 i;

    for (i = 0; i < (uint8)(sizeof(io_init) / sizeof(io_init[0])); ++i)
        hal_gpio_pull_set(io_init[i].pin, io_init[i].type);

    DCDC_CONFIG_SETTING(0x0a);
    DCDC_REF_CLK_SETTING(1);
    DIG_LDO_CURRENT_SETTING(0x01);

    /* AC6 MAP is gated so ER_IROM1, including the 1 KiB stack, must remain
     * entirely below 0x1fff8000. */
    (void)hal_pwrmgr_RAM_retention(RET_SRAM0);
    (void)hal_pwrmgr_RAM_retention_set();
    hal_pwrmgr_LowCurrentLdo_enable();
}

static void ble_memory_init(void)
{
    osal_mem_set_heap((osalMemHdr_t *)g_largeHeap, LARGE_HEAP_SIZE);
    LL_InitConnectContext(pConnContext,
                          g_pConnectionBuffer,
                          BLE_MAX_ALLOW_CONNECTION,
                          BLE_MAX_ALLOW_PKT_PER_EVENT_TX,
                          BLE_MAX_ALLOW_PKT_PER_EVENT_RX,
                          BLE_PKT_VERSION);
    Host_InitContext(MAX_NUM_LL_CONN,
                     glinkDB, glinkCBs,
                     smPairingParam,
                     gMTU_Size,
                     gAuthenLink,
                     l2capReassembleBuf, l2capSegmentBuf,
                     gattClientInfo,
                     gattServerInfo);
}

static void dpls_rf_init(void)
{
    g_rfPhyTxPower = RF_PHY_TX_POWER_N2DBM;
    g_rfPhyPktFmt = PKT_FMT_BLE1M;
    g_rfPhyFreqOffSet = RF_PHY_FREQ_FOFF_00KHZ;
    XTAL16M_CAP_SETTING(0x09);
    XTAL16M_CURRENT_SETTING(0x03);
    hal_rc32k_clk_tracking_init();
    hal_rom_boot_init();
    NVIC_SetPriority((IRQn_Type)BB_IRQn, IRQ_PRIO_REALTIME);
    NVIC_SetPriority((IRQn_Type)TIM1_IRQn, IRQ_PRIO_HIGH);
    NVIC_SetPriority((IRQn_Type)TIM2_IRQn, IRQ_PRIO_HIGH);
    NVIC_SetPriority((IRQn_Type)TIM4_IRQn, IRQ_PRIO_HIGH);
    ble_memory_init();
    hal_rfPhyFreqOff_Set();
}

static void platform_init(void)
{
    xflash_Ctx_t flash_cfg = {.rd_instr = XFRD_FCMD_READ_DUAL};

    low_power_io_init();
    clk_init(g_system_clk);
    hal_rtc_clock_config((CLK32K_e)g_clk32K_config);
    (void)hal_pwrmgr_init();
    hal_spif_cache_init(flash_cfg);
    LOG_INIT();
    (void)hal_gpio_init();
}

int main(void)
{
    watchdog_config(WDG_2S);
    g_system_clk = SYS_CLK_XTAL_16M;
    g_clk32K_config = CLK_32K_RCOSC;
#if (FLASH_PROTECT_FEATURE == 1)
    hal_flash_lock();
#endif
    drv_irq_init();
    init_config();

    {
        extern void ll_patch_slave(void);
        ll_patch_slave();
    }
#if (CFG_HCLK_DYNAMIC_CHANGE)
    {
        extern void ll_patch_hclk_dynamic_chg(void);
        ll_patch_hclk_dynamic_chg();
    }
#endif

    dpls_rf_init();
    platform_init();
    return app_main();
}