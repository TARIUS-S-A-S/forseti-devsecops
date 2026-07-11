// Login programático a Jenkins + captura de dashboard y job
const puppeteer = require('puppeteer-core');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

(async () => {
  const [password, outPath] = process.argv.slice(2);
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 900 });

  console.log('→ Jenkins login page...');
  await page.goto('http://localhost:8090/login', { waitUntil: 'networkidle2', timeout: 30000 });

  console.log('→ Escribiendo credenciales...');
  await page.type('input[name="j_username"]', 'admin');
  await page.type('input[name="j_password"]', password);

  console.log('→ Login...');
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 20000 }),
    page.click('button[name="Submit"]')
  ]);

  await new Promise(r => setTimeout(r, 2000));

  console.log(`→ Screenshot dashboard → ${outPath}`);
  await page.screenshot({ path: outPath, fullPage: false });

  // Job detail
  const jobPath = outPath.replace('dashboard', 'job');
  console.log('→ Navegando al job...');
  await page.goto('http://localhost:8090/job/forseti-devsecops-pipeline/', { waitUntil: 'networkidle2', timeout: 20000 });
  await new Promise(r => setTimeout(r, 2000));
  await page.setViewport({ width: 1600, height: 1100 });
  await new Promise(r => setTimeout(r, 500));
  console.log(`→ Screenshot job → ${jobPath}`);
  await page.screenshot({ path: jobPath, fullPage: false });

  await browser.close();
  console.log('✅ Listo');
})().catch(err => { console.error('❌', err.message); process.exit(1); });
