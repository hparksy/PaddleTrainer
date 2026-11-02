QT += widgets
CONFIG += c++17
TARGET = PaddleTrainer
SOURCES += main.cpp
HEADERS += morse.h
LIBS += ole32.lib avrt.lib
msvc: QMAKE_CXXFLAGS += /utf-8
DEFINES += APP_VERSION=\\\"$$cat($$PWD/VERSION)\\\"  # the VERSION file is the one version number of both apps
