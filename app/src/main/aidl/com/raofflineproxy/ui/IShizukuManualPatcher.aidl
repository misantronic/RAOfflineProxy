package com.raofflineproxy.ui;

interface IShizukuManualPatcher {
    void destroy() = 16777114;
    String execute(String requestJson) = 1;
}
