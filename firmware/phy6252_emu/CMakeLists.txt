cmake_minimum_required(VERSION 3.16)

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    project(phy6252_emu C)
    set(CMAKE_C_STANDARD 99)
    enable_testing()
endif()

add_library(phy6252_emu STATIC phy6252_emu.c)
target_include_directories(phy6252_emu PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(phy6252_emu PRIVATE -Wall -Wextra -Werror)

add_executable(phy6252_emu_host phy6252_emu_host.c)
target_link_libraries(phy6252_emu_host PRIVATE phy6252_emu)
target_compile_options(phy6252_emu_host PRIVATE -Wall -Wextra -Werror)

add_executable(test_phy6252_emu test_phy6252_emu.c)
target_link_libraries(test_phy6252_emu PRIVATE phy6252_emu)
target_compile_options(test_phy6252_emu PRIVATE -Wall -Wextra -Werror)
add_test(NAME test_phy6252_emu COMMAND test_phy6252_emu)
