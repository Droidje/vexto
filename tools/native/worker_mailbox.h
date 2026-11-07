// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "json.hpp"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <functional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <sys/stat.h>

namespace vexto {
using json = nlohmann::ordered_json;

inline std::string read_file(const std::string & path, size_t limit) {
    std::ifstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("Cannot read request input");
    std::string value;
    char buffer[4096];
    while (file.read(buffer, sizeof(buffer)) || file.gcount()) {
        value.append(buffer, size_t(file.gcount()));
        if (value.size() > limit) throw std::runtime_error("Request input exceeds bound");
    }
    return value;
}

inline void write_file(const std::string & path, const std::string & value) {
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file || !file.write(value.data(), value.size())) throw std::runtime_error("Cannot write request output");
    file.close();
    if (!file) throw std::runtime_error("Cannot close request output");
}

inline std::string option(const std::vector<std::string> & args, const std::string & name,
                          const std::string & fallback = "") {
    for (size_t i = 0; i + 1 < args.size(); ++i) if (args[i] == name) return args[i + 1];
    return fallback;
}
inline bool flag(const std::vector<std::string> & args, const std::string & name) {
    for (const auto & arg : args) if (arg == name) return true;
    return false;
}

/** One request in flight per worker. Models and contexts live outside this loop. */
inline void serve(const std::string & directory,
                  const std::function<std::string(const std::vector<std::string> &)> & generate) {
    const auto request = directory + "/request.json";
    const auto response = directory + "/response.json";
    while (true) {
        struct stat st {};
        if (stat(request.c_str(), &st) != 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }
        json result;
        try {
            auto data = json::parse(read_file(request, 65536));
            auto args = data.at("args").get<std::vector<std::string>>();
            if (args.size() > 128) throw std::runtime_error("Too many worker arguments");
            for (const auto & arg : args) if (arg.size() > 8192 || arg.find('\0') != std::string::npos)
                throw std::runtime_error("Invalid worker argument");
            auto output = generate(args);
            if (output.size() > 8192) throw std::runtime_error("Worker response exceeds bound");
            write_file(data.at("stdout").get<std::string>(), output);
            write_file(data.at("stderr").get<std::string>(), "");
            result = {{"ok", true}};
        } catch (const std::exception & error) {
            result = {{"ok", false}, {"error", std::string(error.what()).substr(0, 4096)}};
        }
        if (std::remove(request.c_str()) != 0) throw std::runtime_error("Cannot acknowledge worker input");
        const auto pending = directory + "/response.pending";
        write_file(pending, result.dump());
        if (std::rename(pending.c_str(), response.c_str()) != 0) throw std::runtime_error("Cannot publish worker response");
    }
}
}
