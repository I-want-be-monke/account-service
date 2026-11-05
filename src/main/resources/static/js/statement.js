'use strict';
document.addEventListener('DOMContentLoaded', function () {
    var printButton = document.getElementById('print-statement');
    if (printButton) printButton.addEventListener('click', function () { window.print(); });
});
