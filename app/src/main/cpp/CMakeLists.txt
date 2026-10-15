cmake_minimum_required(VERSION 3.22.1)

project(byedpi_native)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(byedpi SHARED ${BYE_DPI_SRC} native-lib.c)
target_include_directories(byedpi PRIVATE byedpi)

target_compile_options(byedpi PRIVATE -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

target_link_libraries(byedpi PRIVATE android log)

set(TPWS_SRC
    zapret/tpws.c
    zapret/tpws_conn.c
    zapret/pools.c
    zapret/protocol.c
    zapret/tamper.c
    zapret/helpers.c
    zapret/hostlist.c
    zapret/ipset.c
    zapret/params.c
    zapret/sec.c
    zapret/redirect.c
    zapret/resolver.c
    zapret/gzip.c
    zapret/andr/getifaddrs.c
    zapret/andr/netlink.c
)

add_executable(tpws ${TPWS_SRC})
set_target_properties(tpws PROPERTIES
    OUTPUT_NAME "tpws"
    SUFFIX ".so"
    PREFIX "lib"
)
target_include_directories(tpws PRIVATE zapret zapret/andr)
target_compile_options(tpws PRIVATE -std=gnu99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -ffunction-sections -fdata-sections)
target_compile_definitions(tpws PRIVATE __ANDROID__)
target_link_libraries(tpws PRIVATE log z)
