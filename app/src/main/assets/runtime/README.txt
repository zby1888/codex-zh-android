The ARM64 bootstrap archive and its bootstrap-manifest.json are embedded by
the Build private ARM64 runtime GitHub Actions workflow after the source-built
archive passes SHA-256, path and symlink validation.

This source checkout intentionally does not contain a generated binary archive.
