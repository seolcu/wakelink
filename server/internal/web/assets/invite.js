// Copy-to-clipboard for the invite page. Kept as a file (not inline) so the
// page's Content-Security-Policy can stay script-src 'self'.
(function () {
  var button = document.getElementById("copy-code");
  if (!button || !navigator.clipboard) {
    if (button) button.hidden = true;
    return;
  }
  button.addEventListener("click", function () {
    navigator.clipboard.writeText(button.dataset.code).then(function () {
      button.textContent = "복사됨";
      setTimeout(function () { button.textContent = "복사"; }, 2000);
    });
  });
})();
