// Copy-to-clipboard for the invite page. Kept as a file (not inline) so the
// page's Content-Security-Policy can stay script-src 'self'.
(function () {
  var button = document.getElementById("copy-code");
  if (!button || !navigator.clipboard) {
    if (button) button.hidden = true;
    return;
  }
  // Labels come from the page (data-copied / the button text) so this
  // script stays language-neutral.
  var label = button.textContent;
  button.addEventListener("click", function () {
    navigator.clipboard.writeText(button.dataset.code).then(function () {
      button.textContent = button.dataset.copied;
      setTimeout(function () { button.textContent = label; }, 2000);
    });
  });
})();
