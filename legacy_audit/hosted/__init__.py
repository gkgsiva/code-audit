"""The hosted version: one shared web site where project owners log in, open or create an audit
project under a top-level audits folder, and scan and build reports in the browser.

``legacy-audit host`` starts it (the Docker image runs that command). Each project is an ordinary
workspace served by the local GUI app (``legacy_audit.gui.app``) under ``/p/<project>/``; this
package adds the login, the project list, the admin page, the shared scan queue, the status and
logs pages, and the admin defaults merged into every project's ``audit.yaml``.
"""
