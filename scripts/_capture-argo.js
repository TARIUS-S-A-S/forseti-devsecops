// Login programático a Argo CD + captura de la app view
const puppeteer = require('puppeteer-core');
const path = require('path');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

(async () => {
  const [password, outPath] = process.argv.slice(2);
  if (!password) { console.error('uso: node _capture-argo.js <password> <out.png>'); process.exit(1); }

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    ignoreHTTPSErrors: true,
    args: ['--ignore-certificate-errors', '--no-sandbox', '--disable-setuid-sandbox']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1000 });

  console.log('→ Abriendo Argo CD login...');
  await page.goto('https://localhost:8080/login', { waitUntil: 'networkidle2', timeout: 30000 });

  console.log('→ Escribiendo credenciales...');
  await page.waitForSelector('input[name="username"]', { timeout: 10000 });
  await page.type('input[name="username"]', 'admin');
  await page.type('input[name="password"]', password);

  console.log('→ Enviando login...');
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 20000 }),
    page.click('button[type="submit"]')
  ]);

  console.log('→ Esperando applications list...');
  await page.waitForTimeout ? await page.waitForTimeout(3000) : await new Promise(r => setTimeout(r, 3000));
  await page.goto('https://localhost:8080/applications', { waitUntil: 'networkidle2', timeout: 20000 });
  await new Promise(r => setTimeout(r, 3000));

  console.log(`→ Screenshot list → ${outPath}`);
  await page.screenshot({ path: outPath, fullPage: false });

  // También la vista detallada de la app forseti-staging
  const detailPath = outPath.replace('.png', '-detail.png');
  console.log('→ Navegando al detalle de forseti-staging...');
  await page.goto('https://localhost:8080/applications/forseti-staging', { waitUntil: 'networkidle2', timeout: 20000 });
  await new Promise(r => setTimeout(r, 5000));

  console.log(`→ Screenshot detail → ${detailPath}`);
  await page.setViewport({ width: 1600, height: 1400 });
  await new Promise(r => setTimeout(r, 1000));
  await page.screenshot({ path: detailPath, fullPage: false });

  await browser.close();
  console.log('✅ Listo');
})().catch(err => { console.error('❌', err.message); process.exit(1); });
