'use strict';

const assert = require('node:assert/strict');
const { execFileSync, spawn } = require('node:child_process');
const { createHash, randomUUID, X509Certificate } = require('node:crypto');
const fs = require('node:fs');
const https = require('node:https');
const path = require('node:path');
const { setTimeout: wait } = require('node:timers/promises');

const BASE = 'https://localhost:18448';
const PROJECT = 'account-service-e11';
const DATABASE = 'account_service_e11';
const APP = `${PROJECT}-app-1`;
const DB = `${PROJECT}-db-1`;
const browserNames = ['chrome', 'edge', 'firefox'];
const roleLabels = {
  ADMIN: 'Администратор', OPERATOR: 'Оператор', ACCOUNTANT: 'Бухгалтер-расчетчик',
  MANAGER: 'Руководитель', CLIENT: 'Клиент',
};
const browserDefaults = {
  chrome: ['Google', 'Chrome', 'Application', 'chrome.exe'],
  edge: ['Microsoft', 'Edge', 'Application', 'msedge.exe'],
  firefox: ['Mozilla Firefox', 'firefox.exe'],
  yandex: ['Yandex', 'YandexBrowser', 'Application', 'browser.exe'],
};
const stepNames = [
  'start', 'admin', 'operator-create', 'accountant', 'finance-form-error', 'finance-post', 'corrections', 'history',
  'statement', 'statement-error', 'statement-csv', 'statement-print', 'statement-layout',
  'manager-report', 'report-empty', 'report-csv', 'report-print', 'report-layout', 'manager-denied', 'client',
];

function options(args) {
  const result = {};
  const keys = new Set(['browser', 'chrome', 'edge', 'firefox', 'chrome-driver', 'edge-driver', 'firefox-driver',
    'yandex', 'yandex-driver', 'ca', 'spki', 'output', 'env', 'client']);
  for (let i = 0; i < args.length; i++) {
    const key = args[i].slice(2);
    if (!args[i].startsWith('--') || !keys.has(key) || !args[i + 1] || Object.hasOwn(result, key)) {
      throw new Error(`Unknown or incomplete option ${args[i]}`);
    }
    result[key] = args[++i];
  }
  const browser = result.browser || 'all';
  assert(browser === 'all' || Object.hasOwn(browserDefaults, browser), 'Select all, chrome, edge, firefox, or yandex');
  assert(browser !== 'yandex' || result['yandex-driver'], 'Specify the official --yandex-driver executable');
  const client = result.client || 'e11-client-001';
  assert(/^e11-client-\d{3}$/.test(client), 'Use a seeded E11 client login');
  return { ...result, browser, client };
}

function docker(...args) {
  try { return execFileSync('docker.exe', args, { encoding: 'utf8', timeout: 20_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim(); }
  catch { throw new Error(`Docker ${args[0]} failed while verifying the E11 stand`); }
}

function checkStand() {
  for (const [container, service] of [[APP, 'app'], [DB, 'db']]) {
    const identity = docker('inspect', '--format', '{{index .Config.Labels `com.docker.compose.project`}}|'
      + '{{index .Config.Labels `com.docker.compose.service`}}|{{.State.Running}}', container);
    assert.equal(identity, `${PROJECT}|${service}|true`, `Unexpected container ${container}`);
    assert.equal(docker('inspect', '--format', '{{range $key,$value := .NetworkSettings.Networks}}{{$key}} {{end}}', container),
      `${PROJECT}_default`, `Unexpected network for ${container}`);
  }
  assert.equal(docker('port', APP, '18448/tcp'), '127.0.0.1:18448');
  assert.equal(docker('port', DB, '5432/tcp'), '127.0.0.1:55438');
  assert.equal(docker('inspect', '--format', '{{range .Mounts}}{{if eq .Destination `/var/lib/postgresql/data`}}'
    + '{{.Name}}{{end}}{{end}}', DB), `${PROJECT}_account-data`);
  const env = docker('inspect', '--format', '{{range .Config.Env}}{{println .}}{{end}}', APP).split(/\r?\n/);
  assert(['DB_HOST=db', 'DB_PORT=5432', `DB_NAME=${DATABASE}`].every(value => env.includes(value)), 'Unexpected app database');
  assert(!env.some(line => /^(SPRING_DATASOURCE_URL|SPRING_CONFIG_LOCATION|SPRING_CONFIG_ADDITIONAL_LOCATION|SPRING_APPLICATION_JSON)=/.test(line)
    || (/^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)=/.test(line) && line.includes('-Dspring.'))),
  'Unexpected Spring configuration override');
  assert(['null', '[]'].includes(docker('inspect', '--format', '{{json .Config.Cmd}}', APP)), 'Unexpected app command override');
  assert.equal(docker('exec', DB, 'psql', '-XqAt', '--no-password', '-U', 'account_owner', '-d', DATABASE,
    '-c', 'select current_database()'), DATABASE);
}

function readSettings(filename) {
  const settings = new Map();
  for (const line of fs.readFileSync(filename, 'utf8').split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue;
    const match = /^([A-Z_]+)=(.+)$/.exec(line);
    assert(match && !settings.has(match[1]), 'Invalid E11 environment file');
    settings.set(match[1], match[2]);
  }
  assert.equal(settings.get('DB_NAME'), DATABASE);
  for (const role of Object.keys(roleLabels)) {
    assert(/^[0-9a-f]{48}$/.test(settings.get(`DEMO_${role}_PASSWORD`) || ''), `Missing E11 role password ${role}`);
  }
  return settings;
}

function checkHttps(ca, pin) {
  return new Promise((resolve, reject) => {
    const request = https.get(`${BASE}/actuator/health/readiness`, { ca, rejectUnauthorized: true, agent: false }, response => {
      try {
        assert.equal(response.statusCode, 200, 'E11 readiness HTTP status');
        if (pin) {
          const certificate = new X509Certificate(response.socket.getPeerCertificate().raw);
          const key = certificate.publicKey.export({ type: 'spki', format: 'der' });
          assert.equal(createHash('sha256').update(key).digest('base64'), pin, 'Chromium pin differs from the verified TLS leaf');
        }
        let body = '';
        response.setEncoding('utf8').on('data', chunk => { body += chunk; });
        response.on('end', () => {
          try { assert.equal(JSON.parse(body).status, 'UP'); resolve(); }
          catch (error) { reject(error); }
        });
      } catch (error) { response.resume(); reject(error); }
    });
    request.setTimeout(10_000, () => request.destroy(new Error('E11 TLS readiness timed out')));
    request.on('error', reject);
  });
}

function executable(selected, kind) {
  if (selected) {
    const filename = path.resolve(selected);
    assert(fs.existsSync(filename) && fs.statSync(filename).isFile(), `Missing executable ${filename}`);
    return filename;
  }
  if (!kind) return null;
  for (const directory of [process.env.ProgramFiles, process.env['ProgramFiles(x86)'], process.env.LOCALAPPDATA]) {
    if (!directory) continue;
    const filename = path.join(directory, ...browserDefaults[kind]);
    if (fs.existsSync(filename)) return filename;
  }
  throw new Error(`No installed ${kind} executable found. Supply --${kind} <path>.`);
}

function yandexProduct(binary, webdriver) {
  const file = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    '(Get-Item -LiteralPath $env:E11_UI_YANDEX_BINARY).VersionInfo | '
    + 'Select-Object ProductName,ProductVersion,CompanyName,OriginalFilename | ConvertTo-Json -Compress'], {
    encoding: 'utf8', timeout: 10_000, env: { ...process.env, E11_UI_YANDEX_BINARY: binary },
  });
  const product = JSON.parse(file);
  assert.match(product.CompanyName || '', /^YANDEX\b/i, 'Executable is not from Yandex');
  assert.match(product.ProductName || '', /Yandex|Яндекс/i, 'Executable is not branded Yandex');
  assert.equal(product.OriginalFilename.toLowerCase(), 'browser.exe', 'Expected a Yandex Browser executable');
  return { ...product, driverExecutableVersion: execFileSync(webdriver, ['--version'], { encoding: 'utf8', timeout: 10_000 }).trim() };
}

function outputDirectory(input) {
  const base = fs.realpathSync('.local');
  const directory = path.resolve(input || `.local/e11-verification/ui-${new Date().toISOString().replaceAll(':', '-')}-${randomUUID().slice(0, 6)}`);
  function under(candidate) {
    const relative = path.relative(base, candidate);
    return relative && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
  }
  assert(under(directory), 'Evidence must stay under .local');
  let existing = path.dirname(directory);
  while (!fs.existsSync(existing)) existing = path.dirname(existing);
  assert(existing === base || under(fs.realpathSync(existing)), 'Evidence parent resolves outside .local');
  fs.mkdirSync(path.dirname(directory), { recursive: true });
  fs.mkdirSync(directory);
  return directory;
}

function disposeChromiumProfile(directory) {
  const root = fs.realpathSync(directory);
  const profile = path.join(root, 'profile');
  if (!fs.existsSync(profile)) return;
  assert.equal(path.relative(root, fs.realpathSync(profile)), 'profile', 'Unexpected disposable profile target');
  fs.rmSync(profile, { recursive: true, maxRetries: 3, retryDelay: 200 });
}

async function launchYandex(binary, directory, pin) {
  const profile = path.join(directory, 'profile');
  const args = ['--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--enable-automation', '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0', `--user-data-dir=${profile}`];
  if (pin) args.push(`--ignore-certificate-errors-spki-list=${pin}`);
  const process = spawn(binary, [...args, 'about:blank'], { windowsHide: true, stdio: 'ignore' });
  const stop = () => { if (process.exitCode === null && process.signalCode === null) process.kill(); };
  let startupError;
  process.once('error', error => { startupError = error; });
  try {
    const deadline = Date.now() + 10_000;
    const portFile = path.join(profile, 'DevToolsActivePort');
    while (Date.now() < deadline) {
      if (startupError) throw startupError;
      if (process.exitCode !== null) throw new Error(`Yandex startup exited with code ${process.exitCode}`);
      if (fs.existsSync(portFile)) {
        const port = fs.readFileSync(portFile, 'utf8').split(/\r?\n/, 1)[0];
        if (/^\d{1,5}$/.test(port)) return { address: `127.0.0.1:${port}`, stop };
      }
      await wait(100);
    }
    throw new Error('The isolated Yandex debugging port did not open');
  } catch (error) { stop(); throw error; }
}

async function allowYandexTestCertificate(address, ca, pin, tls) {
  assert.equal(typeof WebSocket, 'function', 'The Yandex certificate probe requires Node.js 22.4 or newer');
  const response = await fetch(`http://${address}/json/list`, { signal: AbortSignal.timeout(5000) });
  assert(response.ok, 'Cannot find the isolated Yandex browser tab');
  const tabs = (await response.json()).filter(tab => tab.type === 'page');
  assert.equal(tabs.length, 1, 'Expected exactly one isolated Yandex tab');
  const endpoint = new URL(tabs[0].webSocketDebuggerUrl);
  assert(endpoint.protocol === 'ws:' && ['localhost', '127.0.0.1'].includes(endpoint.hostname)
    && endpoint.port === address.split(':')[1] && endpoint.pathname.startsWith('/devtools/page/'), 'Unexpected Yandex debugger endpoint');
  const socket = new WebSocket(`ws://${address}${endpoint.pathname}`);
  try {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Yandex debugger did not open')), 5000);
      socket.addEventListener('open', () => { clearTimeout(timeout); resolve(); }, { once: true });
      socket.addEventListener('error', () => { clearTimeout(timeout); reject(new Error('Yandex debugger connection failed')); }, { once: true });
    });
    let nextId = 0;
    const pending = new Map();
    function command(method, params) {
      return new Promise((resolve, reject) => {
        const id = ++nextId;
        const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`Yandex debugger ${method} timed out`)); }, 5000);
        pending.set(id, reply => {
          clearTimeout(timeout);
          if (reply.error) reject(new Error(`Yandex debugger ${method}: ${reply.error.message}`));
          else resolve();
        });
        socket.send(JSON.stringify({ id, method, params }));
      });
    }
    socket.addEventListener('message', async event => {
      const reply = JSON.parse(event.data);
      if (pending.has(reply.id)) {
        pending.get(reply.id)(reply);
        pending.delete(reply.id);
      }
      if (reply.method !== 'Security.certificateError') return;
      const { eventId, errorType, requestURL } = reply.params;
      let action = 'cancel';
      try {
        if (errorType === 'ERR_CERT_AUTHORITY_INVALID' && new URL(requestURL).origin === BASE) {
          await checkHttps(ca, pin);
          action = 'continue';
        }
      } catch { /* The browser must cancel on verification errors. */ }
      try {
        await command('Security.handleCertificateError', { eventId, action });
        tls[action === 'continue' ? 'verifiedEvents' : 'deniedEvents']++;
      } catch (error) { tls.commandError = error.message; }
    });
    await command('Security.enable');
    await command('Security.setOverrideCertificateErrors', { override: true });
    return () => socket.close();
  } catch (error) { socket.close(); throw error; }
}

async function openBrowser(kind, binary, webdriver, directory, pin) {
  const { Builder } = require('selenium-webdriver');
  const engine = kind === 'yandex' ? 'chrome' : kind;
  const builder = new Builder().forBrowser(engine === 'edge' ? 'MicrosoftEdge' : engine);
  // Prevent environment variables from redirecting a local test to Selenium Grid.
  builder.disableEnvironmentOverrides();
  const downloads = path.join(directory, 'downloads');
  fs.mkdirSync(downloads);
  let yandex;
  if (kind === 'firefox') {
    const firefox = require('selenium-webdriver/firefox');
    const opts = new firefox.Options().setBinary(binary).addArguments('-headless').setAcceptInsecureCerts(false);
    opts.setPreference('browser.download.folderList', 2);
    opts.setPreference('browser.download.dir', downloads);
    opts.setPreference('browser.download.useDownloadDir', true);
    opts.setPreference('browser.helperApps.neverAsk.saveToDisk', 'text/csv,application/csv,application/octet-stream');
    opts.setPreference('browser.download.always_ask_before_handling_new_types', false);
    builder.setFirefoxOptions(opts);
    if (webdriver) builder.setFirefoxService(new firefox.ServiceBuilder(webdriver));
  } else {
    const chromium = require(`selenium-webdriver/${engine}`);
    const opts = new chromium.Options().setAcceptInsecureCerts(false);
    if (kind === 'yandex') {
      // A fresh Yandex new-tab page can prevent the driver from creating its first tab session.
      yandex = await launchYandex(binary, directory, pin);
      opts.debuggerAddress(yandex.address);
    } else {
      opts.setBinaryPath(binary).addArguments('--headless=new', '--no-first-run', '--no-default-browser-check',
        '--disable-background-networking', `--user-data-dir=${path.join(directory, 'profile')}`);
      if (pin) opts.addArguments(`--ignore-certificate-errors-spki-list=${pin}`);
      opts.setUserPreferences({ 'download.default_directory': downloads, 'download.prompt_for_download': false,
        'download.directory_upgrade': true });
    }
    if (engine === 'chrome') {
      builder.setChromeOptions(opts);
      if (webdriver) builder.setChromeService(new chromium.ServiceBuilder(webdriver));
    } else {
      builder.setEdgeOptions(opts);
      if (webdriver) builder.setEdgeService(new chromium.ServiceBuilder(webdriver));
    }
  }
  let driver;
  try {
    driver = await builder.build();
    await driver.manage().setTimeouts({ implicit: 0, pageLoad: 25_000, script: 10_000 });
    await driver.manage().window().setRect({ width: 1366, height: 900 });
    if (yandex) await driver.sendDevToolsCommand('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: downloads });
    return { driver, stop: yandex?.stop, debugAddress: yandex?.address };
  } catch (error) {
    try { await driver?.quit(); } finally { yandex?.stop(); }
    throw error;
  }
}

async function runBrowser(kind, config, settings, directory, ca, pin) {
  const { By, Key, until } = require('selenium-webdriver');
  const { Select } = require('selenium-webdriver/lib/select');
  const { parse } = require('csv-parse/sync');
  const evidence = { browser: kind, started: new Date().toISOString(), mode: 'headless Selenium WebDriver',
    steps: [], layout: [], fixtures: {} };
  const documentPrefix = `E11-UI-${kind}-${randomUUID().slice(0, 8)}`;
  const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date());
  const tomorrow = new Date(`${today}T00:00:00Z`);
  tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
  const nextDate = tomorrow.toISOString().slice(0, 10);
  let driver, stopBrowser, debugAddress, closeCertificateProbe;
  let clientId, accountId, accountNumber, chargeId, paymentId;
  const clean = error => {
    let message = String(error?.message || error).split('\n').slice(0, 5).join('\n');
    for (const [name, secret] of settings) if (name.endsWith('_PASSWORD')) message = message.replaceAll(secret, '[redacted]');
    return message;
  };

  const current = async () => {
    const url = new URL(await driver.getCurrentUrl());
    assert.equal(url.origin, BASE, 'Navigation left the E11 origin or TLS failed');
    return url;
  };
  async function go(route) {
    assert(route.startsWith('/') && !route.startsWith('//'), 'Use E11 application-relative paths');
    const destination = new URL(route, BASE);
    assert.equal(destination.origin, BASE);
    await driver.get(destination.href);
    await current();
  }
  const css = selector => driver.wait(until.elementLocated(By.css(selector)), 10_000);
  const text = async selector => (await css(selector)).getText();
  const main = async () => text('main');
  async function screenshot(name) {
    fs.writeFileSync(path.join(directory, `${name}.png`), Buffer.from(await driver.takeScreenshot(), 'base64'));
  }
  async function step(name, run, fatal = true) {
    try {
      await run();
      evidence.steps.push({ name, status: 'PASS' });
      console.log(`${kind}: ${name} PASS`);
    } catch (error) {
      const message = clean(error);
      evidence.steps.push({ name, status: 'FAIL', message });
      console.error(`${kind}: ${name} FAIL ${message}`);
      if (driver) {
        try { await screenshot(`failure-${name}`); } catch { /* Keep the original test failure. */ }
      }
      if (fatal) throw error;
    }
  }
  async function fill(name, value) {
    const field = await css(`[name="${name}"]`);
    await field.clear();
    await field.sendKeys(value);
  }
  async function date(name, value) {
    const field = await css(`input[name="${name}"][type="date"], input[name="${name}"][type="month"]`);
    // W3C date controls have different native keystroke ordering. Keep the actual form submission.
    const applied = await driver.executeScript('arguments[0].value = arguments[1];'
      + 'arguments[0].dispatchEvent(new Event("change", {bubbles:true})); return arguments[0].value;', field, value);
    assert.equal(applied, value, 'Date field rejected the ISO date');
  }
  async function select(name, value) { await new Select(await css(`[name="${name}"]`)).selectByValue(value); }
  async function click(selector) {
    const element = await css(selector);
    const href = await element.getAttribute('href');
    if (href) assert.equal(new URL(href, BASE).origin, BASE, 'Unexpected link destination');
    await element.click();
  }
  async function submit() { await click('main form button[type="submit"]'); }
  async function expectUrl(match, description) {
    return driver.wait(async () => {
      const url = await current();
      return match(url) ? url : false;
    }, 10_000, description, 100);
  }
  async function idFromUrl(kindOfId, query) {
    const pattern = new RegExp(`^/${kindOfId}/([0-9a-f-]{36})$`);
    const url = await expectUrl(candidate => pattern.test(candidate.pathname) && candidate.searchParams.has(query),
      `Expected ${kindOfId} ${query}`);
    return pattern.exec(url.pathname)[1];
  }
  async function logout() {
    await go('/');
    await click('form[action="/logout"] button[type="submit"]');
    await expectUrl(url => url.pathname === '/login' && url.searchParams.has('logout'), 'Logout did not return its confirmation');
  }
  async function yandexTls() {
    await go('/login');
    const initial = await driver.wait(() => driver.executeScript(`return document.querySelector('#username') ? 'login'
      : document.querySelector('#details-button') ? 'warning' : false;`), 10_000, 'Yandex login or certificate warning');
    if (initial === 'login') {
      evidence.tls = { origin: BASE, method: 'browser trust' };
      return;
    }
    assert(pin, 'Yandex site exception requires --spki for the verified E11 TLS leaf');
    assert.equal((await current()).href, `${BASE}/login`);
    const warning = await driver.executeScript('return { location: location.href, text: document.body.innerText };');
    assert.equal(new URL(warning.location).protocol, 'chrome-error:', 'Expected a browser-generated certificate warning');
    assert.match(warning.text, /\blocalhost\b/);
    assert.match(warning.text, /\bERR_CERT_AUTHORITY_INVALID\b/);
    await checkHttps(ca, pin);
    const details = await css('#details-button');
    await details.click();
    const exception = await css('#proceed-link');
    assert(await exception.isDisplayed(), 'Yandex did not offer a visible site exception');
    assert.match(await exception.getText(), /Сделать исключение для этого сайта|Make an exception for this site/i);
    await screenshot('tls-site-exception');
    await exception.click();
    await go('/login');
    if ((await driver.findElements(By.id('username'))).length === 0) {
      const tls = { origin: BASE, method: 'per-request certificate handling in one disposable browser tab',
        preflight: 'validated CA, hostname and exact leaf SPKI', warning: 'ERR_CERT_AUTHORITY_INVALID', verifiedEvents: 0, deniedEvents: 0 };
      evidence.tls = tls;
      closeCertificateProbe = await allowYandexTestCertificate(debugAddress, ca, pin, tls);
      await go('/login');
      await css('#username');
      assert(tls.verifiedEvents > 0 && !tls.commandError, 'Yandex certificate error was not verified');
      return;
    }
    assert.equal((await current()).href, `${BASE}/login`);
    evidence.tls = { origin: BASE, method: 'browser site exception in disposable profile',
      preflight: 'validated CA, hostname and exact leaf SPKI', warning: 'ERR_CERT_AUTHORITY_INVALID' };
  }
  async function login(role) {
    await go('/login');
    const user = await css('#username');
    const pass = await css('#password');
    assert.equal(await pass.getAttribute('type'), 'password');
    assert(await driver.executeScript('return arguments[0].labels.length > 0 && arguments[1].labels.length > 0;', user, pass));
    if (role === 'ADMIN') await screenshot('login');
    await user.sendKeys(role === 'CLIENT' ? config.client : `demo-${role.toLowerCase()}`);
    await driver.actions().sendKeys(Key.TAB).perform();
    assert.equal(await (await driver.switchTo().activeElement()).getAttribute('id'), 'password', 'Tab did not focus password');
    await pass.sendKeys(settings.get(`DEMO_${role}_PASSWORD`));
    await driver.actions().sendKeys(Key.TAB).perform();
    const active = await driver.switchTo().activeElement();
    assert.equal(await active.getTagName(), 'button', 'Tab did not reach the sign-in button');
    await driver.actions().sendKeys(Key.ENTER).perform();
    await expectUrl(url => url.pathname === '/', 'Sign-in did not reach home');
    assert((await main()).includes(roleLabels[role]), `Role ${role} was not shown`);
  }
  async function denied(route) {
    await go(route);
    assert(/Доступ запрещ[её]н|недоступен|Недостаточно прав/i.test(await main()), 'Expected visible access denial');
  }
  async function confirmDraft() {
    const id = await idFromUrl('operations', 'created');
    await click(`a[href="/operations/${id}/confirm"]`);
    await submit();
    assert.equal(await idFromUrl('operations', 'posted'), id);
    assert.match(await main(), /Операция проведена/);
    return id;
  }
  async function operation(kindName, amount, suffix) {
    await go(`/accounts/${accountId}/operations/new?kind=${kindName}`);
    await fill('amount', amount);
    await fill('basis', `Проверка UI-01 ${documentPrefix}`);
    await fill('externalNumber', `${documentPrefix}-${suffix}`);
    if (kindName === 'PAYMENT') await select('paymentMethod', 'MANUAL_TRANSFER');
    await submit();
    return confirmDraft();
  }
  async function correction(source, target) {
    await go(`/operations/${source}`);
    await click(`a[href="/operations/${source}/corrections/new"]`);
    await fill('targetAmount', target);
    await fill('reason', `Проверка исправления UI-01 ${documentPrefix}`);
    await fill('basis', `Синтетическая операция ${documentPrefix}`);
    await submit();
    return confirmDraft();
  }
  const statement = id => `/accounts/${id}/statement/result?from=${today}&to=${today}`;
  const movement = id => `/reports/result?type=MOVEMENT&from=${today}&to=${today}&clientId=${id}`;
  async function download(selector, prefix) {
    const dir = path.join(directory, 'downloads');
    const previous = new Set(fs.readdirSync(dir));
    await click(selector);
    const filename = await driver.wait(() => {
      const currentFiles = fs.readdirSync(dir);
      const file = currentFiles.find(name => !previous.has(name) && name.startsWith(prefix) && name.endsWith('.csv'));
      return file && !currentFiles.some(name => name.endsWith('.part') || name.endsWith('.crdownload')) ? file : false;
    }, 18_000, `Expected browser download ${prefix}*.csv`, 250);
    const bytes = fs.readFileSync(path.join(dir, filename));
    assert(bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf])), 'CSV UTF-8 BOM');
    return parse(bytes, { bom: true, columns: true, delimiter: ';', skip_empty_lines: true });
  }
  async function printDocument(name, selector) {
    await click(selector);
    await expectUrl(url => url.pathname.endsWith('/print'), 'Print view link');
    const cssRule = await driver.executeScript(`return Array.from(document.styleSheets).some(sheet => {
      try { return Array.from(sheet.cssRules).some(rule => rule.media?.mediaText.includes('print') &&
        Array.from(rule.cssRules).some(item => item.selectorText?.includes('.no-print') && item.style?.display === 'none')); }
      catch { return false; }
    });`);
    assert(cssRule, 'Print stylesheet did not hide controls');
    await driver.executeScript('window.uiPrintCalls = 0; window.print = () => window.uiPrintCalls++;');
    await click('#print-statement');
    assert.equal(await driver.executeScript('return window.uiPrintCalls;'), 1, 'Print button did not call window.print');
    const pdf = Buffer.from(await driver.printPage({ orientation: 'landscape', background: false,
      width: 21.0, height: 29.7 }), 'base64');
    assert(pdf.length > 1000 && pdf.subarray(0, 5).toString() === '%PDF-' && pdf.includes(Buffer.from('%%EOF')),
      'WebDriver did not produce a valid print PDF envelope');
    fs.writeFileSync(path.join(directory, `${name}.pdf`), pdf);
  }
  async function keyboardTable() {
    const wrapper = await css('main .table-wrap');
    const canScroll = await driver.executeScript(`const wrapper = arguments[0];
      const before = [...document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,[tabindex]')]
        .filter(el => !el.disabled && el.tabIndex >= 0 && el.getClientRects().length > 0 &&
          getComputedStyle(el).visibility === 'visible' &&
          (el.compareDocumentPosition(wrapper) & Node.DOCUMENT_POSITION_FOLLOWING)).at(-1);
      if (wrapper.scrollWidth <= wrapper.clientWidth + 3 || !before) return false;
      before.focus();
      return true;`, wrapper);
    assert(canScroll, 'Narrow table must have a scrollable region and a preceding control');
    await driver.actions().sendKeys(Key.TAB).perform();
    const focus = await driver.executeScript(`const wrapper = arguments[0];
      wrapper.scrollLeft = 0;
      return { active: document.activeElement === wrapper, visible: wrapper.matches(':focus-visible'),
        outline: getComputedStyle(wrapper).outlineStyle };`, wrapper);
    assert(focus.active && focus.visible && focus.outline !== 'none', 'Tab must focus the table region with a visible outline');
    await driver.actions().sendKeys(Key.ARROW_RIGHT).perform();
    const scrollLeft = await driver.wait(() => driver.executeScript('return arguments[0].scrollLeft;', wrapper),
      2500, 'Arrow Right did not scroll the focused table', 50);
    return { ...focus, scrollLeft };
  }
  async function layout(name, route) {
    for (const [viewport, width] of [['desktop', 1366], ['narrow', 520]]) {
      await driver.manage().window().setRect({ width, height: 900 });
      await go(route);
      const result = await driver.executeScript(`
        const width = window.innerWidth;
        const visible = element => element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden';
        const controls = [...document.querySelectorAll('main a, main button, main input:not([type=hidden]), main select, main textarea')]
          .filter(element => visible(element) && !element.closest('.table-wrap'));
        const rects = controls.flatMap(element => [...element.getClientRects()].map(rect => ({element, rect})));
        const outside = rects.filter(({rect}) => rect.left < -2 || rect.right > width + 2).map(({element}) => element.tagName);
        let collisions = 0;
        for (let i=0;i<rects.length;i++) for (let j=i+1;j<rects.length;j++) {
          const a=rects[i], b=rects[j];
          if (a.element.contains(b.element) || b.element.contains(a.element)) continue;
          if (Math.min(a.rect.right,b.rect.right)-Math.max(a.rect.left,b.rect.left)>3 &&
              Math.min(a.rect.bottom,b.rect.bottom)-Math.max(a.rect.top,b.rect.top)>3) collisions++;
        }
        const wrappers = [...document.querySelectorAll('main .table-wrap')].map(element => ({
          width: element.clientWidth, content: element.scrollWidth, overflow: getComputedStyle(element).overflowX}));
        const primary = document.querySelector(arguments[0]);
        const primaryWidth = primary?.getBoundingClientRect().width;
        const overflowingText = [...document.querySelectorAll('main .statement-table th, main .statement-table td')]
          .filter(cell => {
            const bounds = cell.getBoundingClientRect();
            const range = document.createRange();
            range.selectNodeContents(cell);
            return [...range.getClientRects()].some(rect => rect.left < bounds.left - 2 || rect.right > bounds.right + 2);
          }).length;
        return {width, pageWidth:document.documentElement.scrollWidth, collisions, outside, wrappers, primaryWidth, overflowingText};
      `, name === 'report' ? '.statement-table tbody td.statement-text' : '.statement-table tbody tr[data-operation-id] td:first-child');
      evidence.layout.push({ name, viewport, ...result });
      await screenshot(`${name}-${viewport}`);
      if (viewport === 'narrow') evidence.layout.at(-1).keyboard = await keyboardTable();
    }
    const measurements = evidence.layout.filter(item => item.name === name);
    for (const item of measurements) {
      assert(item.viewport !== 'narrow' || item.width <= 650, 'Browser did not enter the narrow viewport');
      assert(item.pageWidth <= item.width + 3 && item.collisions === 0 && item.outside.length === 0,
        `${name} ${item.viewport} layout overflow or overlap ${JSON.stringify(item)}`);
      assert(item.primaryWidth >= (name === 'report' ? 150 : 100) && item.overflowingText === 0,
        `${name} ${item.viewport} has a compressed column or text outside a cell ${JSON.stringify(item)}`);
      assert(item.wrappers.length > 0 && item.wrappers.every(box => box.content <= box.width + 3
        || ['auto', 'scroll'].includes(box.overflow)), 'Table overflow is not contained in a scrolling wrapper');
    }
    await driver.manage().window().setRect({ width: 1366, height: 900 });
  }

  try {
    await step('start', async () => {
      checkStand();
      const binary = executable(config[kind], kind);
      const webdriver = executable(config[`${kind}-driver`]);
      evidence.binary = binary;
      evidence.driverPath = webdriver || 'Selenium Manager';
      if (kind === 'yandex') evidence.product = yandexProduct(binary, webdriver);
      ({ driver, stop: stopBrowser, debugAddress } = await openBrowser(kind, binary, webdriver, directory, pin));
      const caps = await driver.getCapabilities();
      evidence.browserName = caps.get('browserName');
      evidence.browserVersion = caps.get('browserVersion');
      evidence.platform = caps.get('platformName');
      evidence.driverVersion = caps.get('chrome')?.chromedriverVersion || caps.get('msedge')?.msedgedriverVersion
        || caps.get('moz:geckodriverVersion') || null;
      assert(evidence.browserVersion, 'Missing browser version capability');
      if (kind === 'yandex') {
        const runtime = await driver.sendAndGetDevToolsCommand('Browser.getBrowserCommandLine');
        const expected = fs.realpathSync(binary).toLowerCase();
        assert.equal(fs.realpathSync(runtime.arguments[0]).toLowerCase(), expected, 'Session did not launch the Yandex executable');
        evidence.product.runningExecutable = runtime.arguments[0];
        evidence.product.userAgent = await driver.executeScript('return navigator.userAgent;');
        assert.match(evidence.product.userAgent, /\bYaBrowser\//, 'Session did not report a Yandex Browser user agent');
        await yandexTls();
      }
    });
    await step('admin', async () => {
      await login('ADMIN');
      await click('a[href="/admin/users"]');
      assert.match(await main(), /Пользователи/);
      assert((await driver.findElements(By.css('table tbody tr'))).length > 0);
      await go('/reports');
      assert.match(await main(), /Финансовые отчёты/);
      await logout();
    });
    await step('operator-create', async () => {
      await login('OPERATOR');
      await click('a[href="/clients"]');
      await click('a[href="/clients/new"]');
      await select('clientType', 'PERSON');
      await fill('displayName', `Синтетический клиент UI-01 ${documentPrefix}`);
      await fill('identifierType', 'DEMO');
      await fill('identifierValue', documentPrefix);
      await submit();
      clientId = await idFromUrl('clients', 'created');
      await click(`a[href="/clients/${clientId}/accounts/new"]`);
      await submit();
      accountId = await idFromUrl('accounts', 'created');
      accountNumber = (await text('h1')).match(/LS-\d{10}/)?.[0];
      assert(accountNumber, 'Opened account number was not displayed');
      evidence.fixtures = { clientId, accountId, accountNumber, documentPrefix, today };
      await screenshot('operator-account');
      await logout();
    });
    await step('accountant', () => login('ACCOUNTANT'));
    await step('finance-form-error', async () => {
      await go(`/accounts/${accountId}/operations/new?kind=CHARGE`);
      await fill('amount', '0');
      await fill('basis', `Ошибка UI-01 ${documentPrefix}`);
      await submit();
      assert.match(await text('.alert'), /сумм|предел/i);
      await screenshot('finance-error');
    });
    await step('finance-post', async () => {
      chargeId = await operation('CHARGE', '1000.00', 'CHARGE');
      paymentId = await operation('PAYMENT', '400.00', 'PAYMENT');
      Object.assign(evidence.fixtures, { chargeId, paymentId });
    });
    await step('corrections', async () => {
      const chargeCorrection = await correction(chargeId, '900.00');
      const paymentCorrection = await correction(paymentId, '350.00');
      Object.assign(evidence.fixtures, { chargeCorrection, paymentCorrection });
      assert.match(await main(), /550\.00/);
      await screenshot('correction');
    });
    await step('history', async () => {
      await go(`/accounts/${accountId}/operations`);
      assert.match(await main(), /История счета/);
      assert.match(await main(), /550\.00/);
      for (const id of [chargeId, paymentId, evidence.fixtures.chargeCorrection, evidence.fixtures.paymentCorrection]) {
        assert((await driver.findElements(By.css(`a[href="/operations/${id}"]`))).length > 0, `History misses operation ${id}`);
      }
      await screenshot('history');
    });
    await step('statement', async () => {
      await go(`/accounts/${accountId}/statement`);
      await date('from', today);
      await date('to', today);
      await submit();
      assert.equal(await text('#opening-balance'), '0.00');
      assert.equal(await text('#closing-balance'), '550.00');
      assert.equal(await text('#operation-count'), '4');
    });
    await step('statement-error', async () => {
      await go(`/accounts/${accountId}/statement`);
      await date('from', nextDate);
      await date('to', today);
      await submit();
      assert.match(await text('.alert'), /начал|раньше|период|дат/i);
      await screenshot('statement-error');
    });
    await step('statement-csv', async () => {
      await go(statement(accountId));
      const rows = await download('a[href*="/statement.csv"]', 'statement-');
      assert.equal(rows.filter(row => row.record_type === 'OPERATION').length, 4);
      assert.equal(rows.find(row => row.record_type === 'TOTAL')?.closing_balance, '550.00');
    }, false);
    await step('statement-print', async () => {
      await go(statement(accountId));
      await printDocument('statement-print', 'a[href*="/statement/print"]');
    }, false);
    await step('statement-layout', () => layout('statement', statement(accountId)), false);
    await logout();
    await step('manager-report', async () => {
      await login('MANAGER');
      for (const [type, calendar, period, values] of [
        ['CHARGES', 'month', today.slice(0, 7), { netCharges: '900.00' }],
        ['PAYMENTS', 'month', today.slice(0, 7), { netPayments: '350.00' }],
        ['DEBT', 'asOf', today, { debt: '550.00', advance: '0.00', balance: '550.00' }],
      ]) {
        await go(`/reports?type=${type}`);
        await date(calendar, period);
        await fill('clientId', clientId);
        await submit();
        for (const [column, expected] of Object.entries(values)) {
          assert.equal(await text(`[data-total-key="${column}"]`), expected, `${type} ${column}`);
        }
      }
      await go('/reports?type=MOVEMENT');
      await date('from', today);
      await date('to', today);
      await fill('clientId', clientId);
      await submit();
      assert.equal(await text('[data-total-key="closingBalance"]'), '550.00');
      assert.equal(await text(`a[href="/accounts/${accountId}"]`), accountNumber);
    });
    await step('report-empty', async () => {
      await go(movement(randomUUID()));
      assert.match(await main(), /нет счетов/);
      assert.equal(await text('[data-total-key="closingBalance"]'), '0.00');
      await screenshot('report-empty');
    });
    await step('report-csv', async () => {
      await go(movement(clientId));
      const rows = await download('a[href*="/reports.csv"]', 'report-');
      assert.equal(rows.find(row => row.record_type === 'DETAIL')?.account_id, accountId);
      assert.equal(rows.find(row => row.record_type === 'TOTAL')?.closingBalance, '550.00');
    }, false);
    await step('report-print', async () => {
      await go(movement(clientId));
      await printDocument('report-print', 'a[href*="/reports/print"]');
    }, false);
    await step('report-layout', () => layout('report', movement(clientId)), false);
    await step('manager-denied', async () => {
      await denied(`/accounts/${accountId}/operations/new?kind=CHARGE`);
      await screenshot('manager-denied');
      await logout();
    });
    await step('client', async () => {
      await login('CLIENT');
      await click('a[href="/my/accounts"]');
      assert.match(await main(), /Мои лицевые счета/);
      const own = await css('table tbody a[href^="/accounts/"]');
      const ownId = new URL(await own.getAttribute('href')).pathname.split('/').at(-1);
      assert(ownId && ownId !== accountId);
      await own.click();
      await go(statement(ownId));
      assert(await text('#closing-balance'), 'Own statement balance was missing');
      await screenshot('client-own');
      await denied(statement(accountId));
      assert(!(await main()).includes(accountNumber), 'Forbidden page revealed a foreign account');
      await logout();
    });
  } catch (error) {
    if (!evidence.steps.some(item => item.status === 'FAIL' && item.message === clean(error))) {
      evidence.steps.push({ name: 'scenario', status: 'FAIL', message: clean(error) });
    }
  } finally {
    closeCertificateProbe?.();
    if (driver) {
      try {
        await driver.quit();
      }
      catch (error) { evidence.steps.push({ name: 'quit', status: 'FAIL', message: clean(error) }); }
    }
    stopBrowser?.();
    if (kind !== 'firefox') {
      try { disposeChromiumProfile(directory); }
      catch (error) { evidence.profileCleanup = clean(error); }
    }
    for (const name of stepNames) if (!evidence.steps.some(item => item.name === name)) evidence.steps.push({ name, status: 'NOT RUN' });
    evidence.finished = new Date().toISOString();
    evidence.status = evidence.steps.every(item => item.status === 'PASS') ? 'PASS' : 'FAIL';
    fs.writeFileSync(path.join(directory, 'result.json'), JSON.stringify(evidence, null, 2));
  }
  return evidence;
}

async function main() {
  if (process.argv.includes('--help')) {
    console.log('scripts\\ui-test.cmd [--browser all|chrome|edge|firefox|yandex] [--chrome <exe>] [--edge <exe>] [--firefox <exe>]');
    console.log('  [--chrome-driver <exe>] [--edge-driver <exe>] [--firefox-driver <exe>] [--ca <PEM>] [--spki <file or SHA-256 base64>]');
    console.log('  [--yandex <exe>] [--yandex-driver <official exe>]');
    console.log('  [--env .local/e11/stand.env] [--client e11-client-001] [--output .local/e11-verification/ui-unique]');
    return;
  }
  const config = options(process.argv.slice(2));
  process.env.SE_AVOID_BROWSER_DOWNLOAD = 'true';
  process.env.SE_AVOID_STATS = 'true';
  checkStand();
  const settings = readSettings(config.env || '.local/e11/stand.env');
  const pin = config.spki && (fs.existsSync(config.spki) ? fs.readFileSync(config.spki, 'utf8').trim() : config.spki.trim());
  assert(!pin || /^[A-Za-z0-9+/]{43}=$/.test(pin), 'Expected one base64 SHA-256 TLS SPKI pin');
  const ca = fs.readFileSync(config.ca || '.local/e11/tls.pem');
  await checkHttps(ca, pin);
  const output = outputDirectory(config.output);
  process.env.SE_CACHE_PATH = path.join(output, 'webdriver-cache');
  const summary = [];
  for (const kind of config.browser === 'all' ? browserNames : [config.browser]) {
    const directory = path.join(output, kind);
    fs.mkdirSync(directory);
    summary.push(await runBrowser(kind, config, settings, directory, ca, pin));
  }
  fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify({ origin: BASE, database: DATABASE, results: summary }, null, 2));
  console.log(`UI evidence: ${output}`);
  if (summary.some(result => result.status !== 'PASS')) process.exitCode = 2;
}

main().catch(error => { console.error(`UI check stopped: ${error.message}`); process.exitCode = 1; });
