# Included by CMAKE_PROJECT_INCLUDE in the pinned upstream project, after languages initialize.
if(NOT TARGET vexto_nemotron_exec)
    add_executable(vexto_nemotron_exec "${CMAKE_CURRENT_LIST_DIR}/nemotron_main.cpp" "${CMAKE_CURRENT_LIST_DIR}/parent_death.cpp")
    target_compile_features(vexto_nemotron_exec PRIVATE cxx_std_17)
    target_link_libraries(vexto_nemotron_exec PRIVATE nemo_speech_asr_c)
    target_link_options(vexto_nemotron_exec PRIVATE "LINKER:-z,max-page-size=16384")
endif()

# Rename accelerated dependencies too: baseline frontends must never load AVX2 libraries.
if(VEXTO_VARIANT_SUFFIX)
    function(vexto_variant_dependencies)
        foreach(name nemo_speech_asr nemo_speech_asr_c)
            if(TARGET ${name})
                set_target_properties(${name} PROPERTIES OUTPUT_NAME "${name}_${VEXTO_VARIANT_SUFFIX}")
            endif()
        endforeach()
    endfunction()
    cmake_language(DEFER CALL vexto_variant_dependencies)
endif()
