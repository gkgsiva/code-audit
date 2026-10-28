from legacy_audit.cli import app

if __name__ == "__main__":  # guarded: worker processes on Windows re-import this module
    app()
