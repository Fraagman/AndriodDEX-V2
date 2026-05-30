const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const GOOGLE_PLAY_DIR = path.resolve(__dirname, '../../store-listings/google-play/screenshots');
const MS_STORE_DIR = path.resolve(__dirname, '../../store-listings/microsoft-store/screenshots');
const HERO_DIR = path.resolve(__dirname, '../../store-listings');

fs.mkdirSync(GOOGLE_PLAY_DIR, { recursive: true });
fs.mkdirSync(MS_STORE_DIR, { recursive: true });
fs.mkdirSync(HERO_DIR, { recursive: true });

async function takeScreenshots() {
  const browser = await puppeteer.launch({
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();

  // Helper to take a screenshot and optionally crop it
  const snap = async (url, outputPath, width, height, crop = false) => {
    let success = false;
    for (let i = 0; i < 10; i++) {
        try {
            await page.setViewport({ width, height });
            await page.goto(url, { waitUntil: 'networkidle2' });
            success = true;
            break;
        } catch (e) {
            console.log(`Server not ready at ${url}, retrying in 2s...`);
            await new Promise(r => setTimeout(r, 2000));
        }
    }
    if (!success) {
        console.error(`Failed to reach ${url}`);
        return;
    }

    if (crop) {
        await page.screenshot({ path: outputPath, clip: { x: 0, y: 0, width, height } });
    } else {
        await page.screenshot({ path: outputPath });
    }
    console.log(`Saved ${outputPath}`);
  };

  const urls = [
    'http://localhost:3000',
    'http://localhost:3000/devices',
    'http://localhost:3000/policies'
  ];

  // Microsoft Store - 9 PNG files, 16:9, 1920x1080
  for (let i = 0; i < 9; i++) {
    const url = urls[i % urls.length];
    await snap(url, path.join(MS_STORE_DIR, `screenshot_${i+1}.png`), 1920, 1080);
  }

  // Google Play - 8 PNG files, phone (1080x1920) + tablet (1536x2048)
  for (let i = 0; i < 4; i++) {
    const url = urls[i % urls.length];
    await snap(url, path.join(GOOGLE_PLAY_DIR, `phone_${i+1}.png`), 1080, 1920);
  }
  for (let i = 0; i < 4; i++) {
    const url = urls[i % urls.length];
    await snap(url, path.join(GOOGLE_PLAY_DIR, `tablet_${i+1}.png`), 1536, 2048);
  }

  // Hero Image 1024x500
  await snap('http://localhost:3000', path.join(HERO_DIR, `hero.png`), 1024, 500, true);

  await browser.close();
  console.log("All screenshots captured successfully.");
}

takeScreenshots().catch(console.error);
