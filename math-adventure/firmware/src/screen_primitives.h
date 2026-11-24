#pragma once
// Shared by the ESP32 firmware and desktop preview.
void text(const char *s, int x, int baseline, uint16_t color = ink) {
  frameDirty=true;
  if (x < 0 || x + chinese.getUTF8Width(s) > 320 || baseline > 236) Serial.printf("TEXT OVERFLOW x=%d y=%d: %s\n",x,baseline,s);
  chinese.setForegroundColor(color);
  chinese.setCursor(x, baseline);
  chinese.print(s);
}
void centered(const char *s, int baseline) {
  text(s, (320 - chinese.getUTF8Width(s)) / 2, baseline);
}
void button(int x, int y, int w, const char *label, int action, bool primary = true, int height = 44) {
  lcd.fillRoundRect(x, y + 2, w, height, 11, primary ? ink : 0xC671);
  lcd.fillRoundRect(x, y, w, height, 11, primary ? green : pale);
  text(label, x + (w - chinese.getUTF8Width(label)) / 2, y + height / 2 + 6, primary ? TFT_WHITE : ink);
  adventure::registerButton(buttons,buttonCount,{x,y,w,height,action,primary});
}
void face(int x, int y, bool wizard, bool blink = false) {
  frameDirty=true;
  lcd.fillCircle(x, y, 23, wizard ? 0xFE96 : 0x9ED5);
  if (blink) {
    lcd.drawFastHLine(x-10, y-2, 5, ink); lcd.drawFastHLine(x+5, y-2, 5, ink);
  } else {
    lcd.fillCircle(x-8,y-2,2,ink); lcd.fillCircle(x+8,y-2,2,ink);
  }
  lcd.drawLine(x-4,y+7,x,y+9,ink); lcd.drawLine(x,y+9,x+4,y+7,ink);
  lcd.fillCircle(x-15,y+6,4,0xFBAE); lcd.fillCircle(x+15,y+6,4,0xFBAE);
  if (wizard) {
    lcd.fillTriangle(x-25,y-18,x+25,y-18,x-8,y-53,0x8C9B);
    lcd.fillRoundRect(x-29,y-20,58,7,3,0x8C9B);
    lcd.fillCircle(x-8,y-39,3,gold);
  }
}
void star(int x, int y, bool filled) {
  uint16_t color = filled ? gold : 0xC6D2;
  lcd.fillTriangle(x,y-9,x-8,y+7,x+8,y+7,color);
  lcd.fillTriangle(x-10,y-3,x+10,y-3,x,y+10,color);
}
