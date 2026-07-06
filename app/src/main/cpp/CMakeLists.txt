cmake_minimum_required(VERSION 3.18.1)

project(hde_native_tty)

add_library(
        hde-native-tty
        SHARED
        native_tty_port.cpp)

find_library(log-lib log)

target_link_libraries(
        hde-native-tty
        ${log-lib})
