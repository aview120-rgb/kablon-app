import { test, expect } from '@playwright/test';
import { guard, axe, onboard, finishLesson, answer } from './helpers.js';

test('F01-H1 new user onboards in 4 steps and lands on the path', async ({ page }) => {
  const done = guard(page);
  await page.goto('/'); await axe(page);
  await onboard(page);
  await expect(page.getByRole('button', { name: /שיעור 1: שלום ולהתראות. פתוח/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /צעדים ראשונים, שיעור 2: .* נעול/ })).toBeVisible();
  await axe(page); done();
});

test('F01-E1 continue button disabled until a choice is made', async ({ page }) => {
  await page.goto('/'); await page.getByRole('button', { name: 'בואו נתחיל' }).click();
  await expect(page.getByRole('button', { name: 'המשך' })).toBeDisabled();
});

test('F01-E2 A2 level skips unit 1', async ({ page }) => {
  await onboard(page, { level: 'בינוני' });
  await expect(page.getByRole('button', { name: /שיעור 1: אני, אתה.*פתוח/ })).toBeVisible();
});

test('F02-H1 complete a lesson: XP, streak, next lesson unlocked, survives reload', async ({ page }) => {
  const done = guard(page);
  await onboard(page);
  await page.getByRole('button', { name: /שלום ולהתראות/ }).click();
  await axe(page);
  await finishLesson(page);
  await expect(page.getByText('סיימתם את השיעור!')).toBeVisible();
  await expect(page.getByText('100%')).toBeVisible();
  await axe(page);
  await page.getByRole('button', { name: 'המשך' }).click();
  await page.reload();
  await expect(page.getByRole('button', { name: /שיעור 1: שלום ולהתראות. הושלם/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /שיעור 2: נעים להכיר. פתוח/ })).toBeVisible();
  await expect(page.getByLabel('רצף של 1 ימים')).toBeVisible();
  done();
});

test('F02-E1 wrong answer shows correct answer + Hebrew tip and is asked again', async ({ page }) => {
  await onboard(page);
  await page.getByRole('button', { name: /שלום ולהתראות/ }).click();
  const ex = await answer(page, false);
  const sheet = page.getByRole('alertdialog');
  await expect(sheet).toContainText('לא בדיוק');
  await expect(sheet).toContainText(ex.answer);
  await expect(sheet).toContainText('💡');
  await sheet.getByRole('button', { name: 'המשך' }).click();
  const total = await page.evaluate(() => window.__safaCurrent && document.querySelector('[role=progressbar]').getAttribute('aria-valuenow'));
  expect(Number(total)).toBeGreaterThan(0);
});

test('F02-E2 quitting mid-lesson does not save progress', async ({ page }) => {
  await onboard(page);
  await page.getByRole('button', { name: /שלום ולהתראות/ }).click();
  await answer(page, true); await page.getByRole('alertdialog').getByRole('button', { name: 'המשך' }).click();
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: 'יציאה מהשיעור' }).click();
  await expect(page.getByRole('button', { name: /שיעור 1: שלום ולהתראות. פתוח/ })).toBeVisible();
});

test('F02-N1 locked lesson cannot be opened by URL', async ({ page }) => {
  await onboard(page);
  await page.goto('/#/lesson/u9l3');
  await expect(page).toHaveURL(/#\/home/);
});

test('F03 practice: empty state, then review after a lesson', async ({ page }) => {
  await onboard(page);
  await page.getByRole('link', { name: 'תרגול' }).click();
  await expect(page.getByText('עוד אין מילים לתרגל')).toBeVisible();
  await axe(page);
  await page.evaluate(() => { const s = window.__safa.state(); s.words.hello = { box: 1, next: '2000-01-01', wrong: 1, seen: 1 }; s.words.bye = { box: 1, next: '2000-01-01', wrong: 0, seen: 1 }; window.__safa.store.save(); });
  await page.reload();
  await page.getByRole('button', { name: 'התחילו תרגול' }).click();
  await finishLesson(page);
  await expect(page.getByText('סיימתם תרגול!')).toBeVisible();
});

test('S07 words: search and empty result', async ({ page }) => {
  await onboard(page);
  await page.getByRole('button', { name: /שלום ולהתראות/ }).click();
  await finishLesson(page); await page.getByRole('button', { name: 'המשך' }).click();
  await page.getByRole('link', { name: 'מילים' }).click();
  await page.getByRole('searchbox').fill('תודה');
  await expect(page.getByText('thank you')).toBeVisible();
  await page.getByRole('searchbox').fill('qqqq');
  await expect(page.getByText('לא נמצאו מילים')).toBeVisible();
  await axe(page);
});

test('F05/S09 settings: dark mode, font size, delete all data', async ({ page }) => {
  await onboard(page);
  await page.goto('/#/settings'); await axe(page);
  await page.getByLabel('ערכת צבע').selectOption('dark');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await axe(page);
  await page.getByLabel('גודל טקסט').selectOption('1.3');
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: /מחיקת כל הנתונים/ }).click();
  await expect(page.getByRole('button', { name: 'בואו נתחיל' })).toBeVisible();
});

test('S08 profile shows stats and week chart', async ({ page }) => {
  await onboard(page);
  await page.getByRole('link', { name: 'פרופיל' }).click();
  await expect(page.getByRole('img', { name: /נקודות ב־7 הימים/ })).toBeVisible();
  await axe(page);
});

test('S10 legal pages exist and link back', async ({ page }) => {
  for (const p of ['privacy', 'terms', 'accessibility']) {
    await page.goto(`/legal/${p}.html`);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await axe(page);
  }
});

test('keyboard only: can finish the onboarding', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'בואו נתחיל' }).focus(); await page.keyboard.press('Enter');
  await page.getByRole('radio', { name: /לעבודה/ }).focus(); await page.keyboard.press('Space');
  await expect(page.getByRole('button', { name: 'המשך' })).toBeEnabled();
});

test('F02-E3 all answers wrong: lesson still ends (each mistake retried once), low accuracy shown', async ({ page }) => {
  await onboard(page);
  await page.getByRole('button', { name: /שלום ולהתראות/ }).click();
  let n = 0;
  for (; n < 40 && /#\/lesson/.test(page.url()); n++) {
    await answer(page, false);
    await page.getByRole('alertdialog').getByRole('button', { name: 'המשך' }).click();
  }
  expect(n).toBeLessThan(25);
  await expect(page).toHaveURL(/#\/done/);
  await expect(page.getByText('💪')).toBeVisible();
});

test('B04 feedback sheet is removed when leaving the lesson (Android back button)', async ({ page }) => {
  await onboard(page);
  await page.getByRole('button', { name: /שלום ולהתראות/ }).click();
  await answer(page, false);
  await expect(page.getByRole('alertdialog')).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL(/#\/home/);
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
});
