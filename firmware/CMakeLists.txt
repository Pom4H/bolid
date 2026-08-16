cmake_minimum_required(VERSION 3.16)
project(test_dpls_server C)
set(CMAKE_C_STANDARD 99)

option(ENABLE_COVERAGE "Instrument firmware/src for line coverage" OFF)

enable_testing()
add_subdirectory(phy6252_emu)

add_library(test_dpls_server
    src/dpls_protocol.c
    src/dpls_server.c
    src/dpls_safety.c
    src/dpls_led.c
    src/dpls_calib.c
    src/dpls_hmac.c
)
target_include_directories(test_dpls_server PUBLIC include)
target_compile_options(test_dpls_server PRIVATE -Wall -Wextra -Werror)

add_executable(dpls_simulator sim/dpls_simulator.c sim/dpls_sim_board.c)
target_include_directories(dpls_simulator PRIVATE sim)
target_link_libraries(dpls_simulator PRIVATE test_dpls_server phy6252_emu)
target_compile_options(dpls_simulator PRIVATE -Wall -Wextra -Werror)

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

dpls_add_test(test_server_v2 tests/test_server_v2.c)
dpls_add_test(test_identify_contract tests/test_identify_contract.c)
dpls_add_test(test_led tests/test_led.c)
dpls_add_test(test_calib tests/test_calib.c)
dpls_add_test(test_protocol tests/test_protocol.c)
dpls_add_test(test_safety tests/test_safety.c)
dpls_add_test(test_hmac tests/test_hmac.c)

add_executable(test_sim_att tests/test_sim_att.c sim/dpls_sim_board.c)
target_include_directories(test_sim_att PRIVATE sim)
target_link_libraries(test_sim_att PRIVATE test_dpls_server phy6252_emu)
target_compile_options(test_sim_att PRIVATE -Wall -Wextra -Werror)
if(ENABLE_COVERAGE)
    target_compile_options(test_sim_att PRIVATE --coverage)
    target_link_options(test_sim_att PRIVATE --coverage)
endif()
add_test(NAME test_sim_att COMMAND test_sim_att)

add_executable(test_adc_irq_model tests/test_adc_irq_model.c)
target_compile_options(test_adc_irq_model PRIVATE -Wall -Wextra -Werror)
add_test(NAME test_adc_irq_model COMMAND test_adc_irq_model)

if(ENABLE_COVERAGE)
    target_compile_options(test_dpls_server PRIVATE --coverage)
    target_link_options(test_dpls_server PRIVATE --coverage)
    target_compile_options(test_adc_irq_model PRIVATE --coverage)
    target_link_options(test_adc_irq_model PRIVATE --coverage)
endif()
