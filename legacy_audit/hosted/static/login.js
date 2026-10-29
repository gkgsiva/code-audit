/* Login page: the test login asks for a name only; Okta will redirect instead. */
(function () {
  "use strict";
  var form = document.getElementById("login");
  var error = document.getElementById("error");
  var next = new URLSearchParams(location.search).get("next") || "/";
  fetch("/api/auth").then(function (r) { return r.json(); }).then(function (a) {
    if (a.banner) { var b = document.getElementById("banner"); b.textContent = a.banner; b.hidden = false; }
    document.getElementById("signup").textContent = a.open_signup
      ? "First time here? Type the name you want to use; it is created for you."
      : "Only users an admin has added can log in.";
  });
  form.addEventListener("submit", function (e) {
    e.preventDefault();
    error.hidden = true;
    fetch("/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Legacy-Audit": "1" },
      body: JSON.stringify({ name: document.getElementById("name").value, next: next })
    }).then(function (r) {
      return r.json().then(function (d) {
        if (!r.ok) throw new Error(d.detail || r.statusText);
        location.href = d.next || "/";
      });
    }).catch(function (err) { error.textContent = err.message; error.hidden = false; });
  });
})();
