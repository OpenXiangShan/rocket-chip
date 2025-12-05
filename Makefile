FUZZ_TOP  = freechips.rocketchip.system.FuzzMain
BUILD_DIR = $(abspath ./build)

RTL_DIR    = $(BUILD_DIR)/rtl
RTL_SUFFIX = sv
TOP_V      = $(RTL_DIR)/SimTop.$(RTL_SUFFIX)
TOP_FIR    = $(RTL_DIR)/SimTop.fir

CHISEL_ARGS = --target-dir $(RTL_DIR) \
              --full-stacktrace       \
              $(MILL_ARGS)

CHISEL_TARGET ?= systemverilog
CHISEL_ARGS += --target $(CHISEL_TARGET)
ifeq ($(CHISEL_TARGET),systemverilog)
CHISEL_ARGS += --split-verilog
endif

ifneq ($(FIRTOOL),)
CHISEL_ARGS += --firtool-binary-path $(abspath $(FIRTOOL))
endif

# Coverage support
ifneq ($(FIRRTL_COVER),)
comma := ,
splitcomma = $(foreach w,$(subst $(comma), ,$1),$(if $(strip $w),$w))
CHISEL_ARGS += $(foreach c,$(call splitcomma,$(FIRRTL_COVER)),--extract-$(c)-cover)
endif

ifeq ($(GSIM),1)
CHISEL_ARGS += --dump-fir --difftest-config G
endif

BOOTROM_DIR = $(abspath ./bootrom)
BOOTROM_SRC = $(BOOTROM_DIR)/bootrom.S
BOOTROM_IMG = $(BOOTROM_DIR)/bootrom.img

$(BOOTROM_IMG): $(BOOTROM_SRC)
	@make -C $(BOOTROM_DIR) all

bootrom: $(BOOTROM_IMG)

SCALA_FILE = $(shell find ./src/main/scala -name '*.scala')

$(TOP_V): $(SCALA_FILE) $(BOOTROM_IMG)
	mill -i rocketchip.runMain $(FUZZ_TOP) $(CHISEL_ARGS)
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
	@$(MAKE) -C difftest emu WITH_CHISELDB=0 WITH_CONSTANTIN=0

simv: sim-verilog
	@$(MAKE) -C difftest simv WITH_CHISELDB=0 WITH_CONSTANTIN=0

clean:
	rm -rf $(BUILD_DIR)

bsp:
	mill -i mill.bsp.BSP/install

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
