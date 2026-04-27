.PHONY: all test clean test_pe test_systolic test_vector test_cube test_integration

TESTS = test_pe test_systolic test_vector test_cube test_integration

all: test

test:
	@for t in $(TESTS); do \
		echo "===== Running $$t ====="; \
		$(MAKE) -C tb/$$t || exit 1; \
	done
	@echo ""
	@echo "===== ALL TESTS PASSED ====="

$(TESTS):
	$(MAKE) -C tb/$@

clean:
	@for t in $(TESTS); do $(MAKE) -C tb/$$t clean 2>/dev/null; done
	@find . -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null || true
	@find . -name "*.xml" -path "*/tb/*" -delete 2>/dev/null || true
