cmake_minimum_required(VERSION 3.22.1)

project(zdpi_native)

file(GLOB ZDPI_SRC
    zdpi/*.c
    zdpi/crypto/*.c
)
list(REMOVE_ITEM ZDPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/zdpi/win_service.c)

# 1. JNI Shared library (libbyedpi.so) for in-process proxy execution
add_library(byedpi SHARED ${ZDPI_SRC} native-lib.c)
target_include_directories(byedpi PRIVATE zdpi zdpi/crypto .)
target_compile_options(byedpi PRIVATE -std=gnu99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter)
target_compile_definitions(byedpi PRIVATE ANDROID_APP __ANDROID__)
target_link_libraries(byedpi PRIVATE android log)
target_link_options(byedpi PRIVATE "-Wl,--wrap=daemon")

# 2. Standalone executable built as libtpws.so so ZapretService/ProcessBuilder runs zDPI directly
add_executable(tpws ${ZDPI_SRC})
set_target_properties(tpws PROPERTIES
    OUTPUT_NAME "tpws"
    SUFFIX ".so"
    PREFIX "lib"
)
target_include_directories(tpws PRIVATE zdpi zdpi/crypto .)
target_compile_options(tpws PRIVATE -std=gnu99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -ffunction-sections -fdata-sections)
target_compile_definitions(tpws PRIVATE ANDROID_APP __ANDROID__)
target_link_libraries(tpws PRIVATE log)

# 3. Standalone executable built as libzdpi.so
add_executable(zdpi ${ZDPI_SRC})
set_target_properties(zdpi PROPERTIES
    OUTPUT_NAME "zdpi"
    SUFFIX ".so"
    PREFIX "lib"
)
target_include_directories(zdpi PRIVATE zdpi zdpi/crypto .)
target_compile_options(zdpi PRIVATE -std=gnu99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -ffunction-sections -fdata-sections)
target_compile_definitions(zdpi PRIVATE ANDROID_APP __ANDROID__)
target_link_libraries(zdpi PRIVATE log)
