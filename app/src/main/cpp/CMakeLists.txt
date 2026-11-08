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

# 4. TGWS acceleration & Gatik native JNI library (libtgws.so)
add_library(tgws SHARED
    tgws/tgws_accel.cpp
    tgws/gatik.cpp
)
target_include_directories(tgws PRIVATE tgws .)
target_compile_options(tgws PRIVATE -std=c++17 -O3 -Wall -Wextra -Wno-unused-parameter)
target_compile_definitions(tgws PRIVATE ANDROID_APP __ANDROID__)
target_link_libraries(tgws PRIVATE log)

# 5. Standalone Gatik executable built as libgatik.so
add_executable(gatik tgws/gatik.cpp)
set_target_properties(gatik PROPERTIES
    OUTPUT_NAME "gatik"
    SUFFIX ".so"
    PREFIX "lib"
)
target_include_directories(gatik PRIVATE tgws .)
target_compile_options(gatik PRIVATE -std=c++17 -O3 -Wall -Wextra -Wno-unused-parameter -DSTANDALONE_GATIK_BIN)
target_compile_definitions(gatik PRIVATE ANDROID_APP __ANDROID__)
target_link_libraries(gatik PRIVATE log)

