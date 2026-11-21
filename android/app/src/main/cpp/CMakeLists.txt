cmake_minimum_required(VERSION 3.22.1)
project(yatralens_vision LANGUAGES C CXX)

add_library(cactus STATIC IMPORTED)
set_target_properties(cactus PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/libcactus.a")

add_library(yatralens_vision SHARED mirage_jni.cpp)
target_include_directories(yatralens_vision PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
target_compile_features(yatralens_vision PRIVATE cxx_std_17)
target_link_libraries(yatralens_vision PRIVATE cactus log m)
