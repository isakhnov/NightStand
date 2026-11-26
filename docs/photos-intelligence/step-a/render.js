// Render docs/photos-intelligence/step-a/mockup.html screens to PNG (2x).
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const dir = process.argv[2];
  const url = 'file://' + path.join(dir, 'mockup.html');
  const b = await chromium.launch();
  const shots = [['a-day', 915, 412, 'a-photo-swipe-day.png'], ['a-night', 915, 412, 'a-photo-swipe-night.png'],
                 ['b', 412, 915, 'b-photos-options.png'], ['c', 412, 915, 'c-hidden-photos.png'], ['d', 915, 412, 'd-why-shown.png']];
  for (const [id, w, h, out] of shots) {
    const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
    await p.goto(url + '?only=' + id); await p.waitForTimeout(150);
    await p.screenshot({ path: path.join(dir, out), clip: { x: 0, y: 0, width: w, height: h } }); await p.close();
  }
  const p = await b.newPage({ viewport: { width: 1956, height: 900 }, deviceScaleFactor: 1 });
  await p.goto(url); await p.waitForTimeout(150);
  await p.screenshot({ path: path.join(dir, 'overview.png'), fullPage: true });
  await b.close();
})();
