cmake_minimum_required(VERSION 3.16)
project(test_dpls_server C)
set(CMAKE_C_STANDARD 99)

option(ENABLE_COVERAGE "Instrument firmware/src for line coverage" OFF)

add_library(test_dpls_server
    src/dpls_protocol.c
    src/dpls_server.c
    src/dpls_safety.c
    src/dpls_led.c
    src/dpls_calib.c
)
target_include_directories(test_dpls_server PUBLIC include)
target_compile_options(test_dpls_server PRIVATE -Wall -Wextra -Werror)

enable_testing()

function(dpls_add_test name)
    add_executable(${name} ${ARGN})
    target_link_libraries(${name} PRIVATE test_dpls_server)
    target_compile_options(${name} PRIVATE -Wall -Wextra -Werror)
    if(ENABLE_COVERAGE)
        target_compile_options(${name} PRIVATE --coverage)
        target_link_options(${name} PRIVATE --coverage)
    endif()
    add_test(NAME ${name} COMMAND ${name})
endfunction()

dpls_add_test(test_dpls_server_tests tests/test_server.c)
dpls_add_test(test_log_journal tests/test_log_journal.c)
dpls_add_test(test_time_sync tests/test_time_sync.c)
dpls_add_test(test_led tests/test_led.c)
dpls_add_test(test_calib tests/test_calib.c)
dpls_add_test(test_protocol tests/test_protocol.c)
dpls_add_test(test_server_edges tests/test_server_edges.c)
dpls_add_test(test_safety tests/test_safety.c)

add_executable(test_adc_irq_model tests/test_adc_irq_model.c)
target_compile_options(test_adc_irq_model PRIVATE -Wall -Wextra -Werror)
add_test(NAME test_adc_irq_model COMMAND test_adc_irq_model)

if(ENABLE_COVERAGE)
    target_compile_options(test_dpls_server PRIVATE --coverage)
    target_link_options(test_dpls_server PRIVATE --coverage)
    target_compile_options(test_adc_irq_model PRIVATE --coverage)
    target_link_options(test_adc_irq_model PRIVATE --coverage)
endif()
