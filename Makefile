CHISEL_VERSION = 6.7.0

FUZZ_TOP  = freechips.rocketchip.system.FuzzMain
BUILD_DIR = $(abspath ./build)

RTL_DIR    = $(BUILD_DIR)/rtl
RTL_SUFFIX = sv
TOP_V      = $(RTL_DIR)/SimTop.$(RTL_SUFFIX)
TOP_FIR    = $(RTL_DIR)/SimTop.fir

MILL_ARGS = --target-dir $(RTL_DIR) \
            --full-stacktrace

ifeq ($(XFUZZ),1)
CHISEL_VERSION = 3.6.1
endif

ifeq ($(CHISEL_VERSION),3.6.1)
RTL_SUFFIX = v
TOP_V      = $(RTL_DIR)/SimTop.$(RTL_SUFFIX)
else
CHISEL_TARGET ?= systemverilog
MILL_ARGS += --target $(CHISEL_TARGET)
ifeq ($(CHISEL_TARGET),systemverilog)
MILL_ARGS += --split-verilog
endif
endif

# Coverage support
ifneq ($(FIRRTL_COVER),)
MILL_ARGS += COVER=$(FIRRTL_COVER)
endif

ifeq ($(filter gsim,$(MAKECMDGOALS)),gsim)
MILL_ARGS += --dump-fir --difftest-config G
endif

BOOTROM_DIR = $(abspath ./bootrom)
BOOTROM_SRC = $(BOOTROM_DIR)/bootrom.S
BOOTROM_IMG = $(BOOTROM_DIR)/bootrom.img

$(BOOTROM_IMG): $(BOOTROM_SRC)
	@make -C $(BOOTROM_DIR) all

bootrom: $(BOOTROM_IMG)

SCALA_FILE = $(shell find ./src/main/scala -name '*.scala')

$(TOP_V): $(SCALA_FILE) $(BOOTROM_IMG)
	mill -i generator[$(CHISEL_VERSION)].runMain $(FUZZ_TOP) $(MILL_ARGS)
ifeq ($(CHISEL_TARGET),systemverilog)
	@cp src/main/resources/vsrc/EICG_wrapper.v $(RTL_DIR)
	@sed -i 's/UNOPTFLAT/LATCH/g' $(RTL_DIR)/EICG_wrapper.v
	@for file in $(RTL_DIR)/*.$(RTL_SUFFIX); do                                  \
		sed -i -e 's/$$fatal/xs_assert_v2(`__FILE__, `__LINE__)/g' "$$file"; \
		sed -i -e "s/\$$error(/\$$fwrite(32\'h80000002, /g" "$$file";        \
	done
endif

sim-verilog: $(TOP_V)

emu: sim-verilog
	@$(MAKE) -C difftest emu WITH_CHISELDB=0 WITH_CONSTANTIN=0 RTL_SUFFIX=$(RTL_SUFFIX)

gsim: sim-verilog
	@$(MAKE) -C difftest gsim WITH_CHISELDB=0 WITH_CONSTANTIN=0 RTL_SUFFIX=$(RTL_SUFFIX)

clean:
	rm -rf $(BUILD_DIR)

idea:
	mill -i mill.idea.GenIdea/idea

init:
	git submodule update --init

# Below is the original rocket-chip Makefile
base_dir=$(abspath ./)

MODEL ?= TestHarness
PROJECT ?= freechips.rocketchip.system
CFG_PROJECT ?= $(PROJECT)
CONFIG ?= $(CFG_PROJECT).DefaultConfig
MILL ?= mill

verilog:
	cd $(base_dir) && $(MILL) -i emulator[freechips.rocketchip.system.TestHarness,$(CONFIG)].mfccompiler.compile

clean-all: clean
	rm -rf out/
