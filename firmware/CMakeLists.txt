cmake_minimum_required(VERSION 3.16)
project(test_dpls_server C)
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

option(ENABLE_COVERAGE "Instrument firmware/src for line coverage" OFF)
option(ENABLE_SANITIZERS "Enable AddressSanitizer and UndefinedBehaviorSanitizer for host tests" OFF)

enable_testing()

if(ENABLE_SANITIZERS)
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        add_compile_options(
            -fsanitize=address,undefined
            -fno-omit-frame-pointer
            -fno-sanitize-recover=all
        )
        add_link_options(
            -fsanitize=address,undefined
            -fno-sanitize-recover=all
        )
    else()
        message(FATAL_ERROR "ENABLE_SANITIZERS requires a GNU-compatible or Clang host compiler")
    endif()
endif()

add_library(test_dpls_server
    src/dpls_protocol.c
    src/dpls_server.c
    src/dpls_safety.c
    src/dpls_led.c
    src/dpls_calib.c
    src/dpls_hmac.c
    src/dpls_durable_settings.c
)
target_include_directories(test_dpls_server PUBLIC include)
target_compile_options(test_dpls_server PRIVATE -Wall -Wextra -Werror)

add_executable(dpls_simulator
    sim/dpls_simulator.c
    sim/dpls_sim_board.c
    sim/dpls_sim_transport.c
)
target_include_directories(dpls_simulator PRIVATE sim)
target_link_libraries(dpls_simulator PRIVATE test_dpls_server)
target_compile_options(dpls_simulator PRIVATE -Wall -Wextra -Werror)
if(ENABLE_COVERAGE)
    # The simulator links the instrumented production-core archive too. Without
    # the gcov runtime here the coverage configuration fails before tests run.
    target_compile_options(dpls_simulator PRIVATE --coverage)
    target_link_options(dpls_simulator PRIVATE --coverage)
endif()

function(dpls_add_test name)
    add_executable(${name} ${ARGN})
    target_link_libraries(${name} PRIVATE test_dpls_server)
    target_compile_options(${name} PRIVATE -Wall -Wextra -Werror)
    if(ENABLE_COVERAGE)
        target_compile_options(${name} PRIVATE --coverage)
        target_link_options(${name} PRIVATE --coverage)
    endif()
    add_test(NAME ${name} COMMAND ${name})
    set_tests_properties(${name} PROPERTIES TIMEOUT 30)
endfunction()

dpls_add_test(test_server_v2 tests/test_server_v2.c)
dpls_add_test(test_server_state_sequences tests/test_server_state_sequences.c)
dpls_add_test(test_auth_lock_fail_safe tests/test_auth_lock_fail_safe.c)
dpls_add_test(test_identify_contract tests/test_identify_contract.c)
dpls_add_test(test_led tests/test_led.c)
dpls_add_test(test_calib tests/test_calib.c)
dpls_add_test(test_protocol tests/test_protocol.c)
dpls_add_test(test_protocol_fuzz tests/test_protocol_fuzz.c)
dpls_add_test(test_safety tests/test_safety.c)
dpls_add_test(test_hmac tests/test_hmac.c)
dpls_add_test(test_durable_settings tests/test_durable_settings.c)
dpls_add_test(test_durable_settings_powerloss_matrix tests/test_durable_settings_powerloss_matrix.c)

add_executable(test_sim_att
    tests/test_sim_att.c
    sim/dpls_sim_board.c
    sim/dpls_sim_transport.c
)
target_include_directories(test_sim_att PRIVATE sim)
target_link_libraries(test_sim_att PRIVATE test_dpls_server)
target_compile_options(test_sim_att PRIVATE -Wall -Wextra -Werror)
if(ENABLE_COVERAGE)
    target_compile_options(test_sim_att PRIVATE --coverage)
    target_link_options(test_sim_att PRIVATE --coverage)
endif()
add_test(NAME test_sim_att COMMAND test_sim_att)
set_tests_properties(test_sim_att PROPERTIES TIMEOUT 30)

add_executable(test_adc_irq_model tests/test_adc_irq_model.c)
target_compile_options(test_adc_irq_model PRIVATE -Wall -Wextra -Werror)
add_test(NAME test_adc_irq_model COMMAND test_adc_irq_model)
set_tests_properties(test_adc_irq_model PROPERTIES TIMEOUT 30)

if(ENABLE_COVERAGE)
    target_compile_options(test_dpls_server PRIVATE --coverage)
    target_link_options(test_dpls_server PRIVATE --coverage)
    target_compile_options(test_adc_irq_model PRIVATE --coverage)
    target_link_options(test_adc_irq_model PRIVATE --coverage)
endif()
