.PHONY: help release

help:
	@echo "make release major|minor|patch - cut a release"
	@echo "  Bumps the version, tags vX.Y.Z and pushes. CI (release.yml) then runs"
	@echo "  the tests, builds and signs the APK and AAB, and publishes the GitHub"
	@echo "  Release. Nothing is built locally. See RELEASING.md."

# The bump type is the second word: make release minor
release:
	@scripts/release.sh $(word 2,$(MAKECMDGOALS))

# Swallow the bump-type word so make does not treat it as a goal.
%:
	@:
