import { expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

export function guard(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  return () => expect(errors, 'console errors').toEqual([]);
}
export async function axe(page) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.length}`)).toEqual([]);
}
export async function onboard(page, { level = 'יודע קצת', age = '18 ומעלה' } = {}) {
  await page.goto('/');
  await page.getByRole('button', { name: 'בואו נתחיל' }).click();
  await page.getByRole('radio', { name: /לטייל/ }).click();
  await page.getByRole('button', { name: 'המשך' }).click();
  await page.getByRole('radio', { name: new RegExp(level) }).click();
  await page.getByRole('button', { name: 'המשך' }).click();
  await page.getByRole('radio', { name: age }).click();
  await page.getByRole('button', { name: /מסכים/ }).click();
  await expect(page).toHaveURL(/#\/home/);
}
// Answers the current exercise correctly (or wrongly) using the exercise data the app exposes for tests.
export async function answer(page, correct = true) {
  await page.locator('.instr').waitFor();
  const ex = await page.evaluate(() => window.__safaCurrent);
  if (ex.type === 'intro') { await page.getByRole('button', { name: /הבנתי/ }).click(); return ex; }
  if (ex.type === 'choice' || ex.type === 'listen') {
    const val = correct ? ex.answer : ex.options.find((o) => o !== ex.answer);
    await page.locator(`.options [data-value="${val.replace(/"/g, '\\"')}"]`).click();
  } else if (ex.type === 'type') {
    await page.getByRole('textbox', { name: 'כתבו באנגלית' }).fill(correct ? ex.answer : 'zzzz');
  } else if (ex.type === 'build') {
    const words = correct ? ex.answer.replace(/[.,!?]/g, ' ').split(/\s+/).filter(Boolean) : [ex.bank.find((w) => true)];
    for (const w of words) await page.locator(`.bank .tile:not(.used)[data-word="${w.replace(/"/g, '\\"')}"]`).first().click();
  } else if (ex.type === 'match') {
    for (const p of ex.pairs) { await page.locator(`[data-side=en][data-en="${p.en}"]`).click(); await page.locator(`[data-side=he][data-en="${p.en}"]`).click(); }
    return ex;
  }
  await page.getByRole('button', { name: 'בדיקה' }).click();
  return ex;
}
export async function finishLesson(page, { wrongFirst = false } = {}) {
  for (let i = 0; i < 40 && /#\/lesson/.test(page.url()); i++) {
    const ex = await answer(page, !(wrongFirst && i === 0));
    if (ex.type !== 'intro') await page.getByRole('alertdialog').getByRole('button', { name: 'המשך' }).click();
  }
  await expect(page).toHaveURL(/#\/done/);
}
