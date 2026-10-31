// All coordinates use the final E1002 canvas, independent of CSS display size.
const cropCanvas = document.getElementById('crop-canvas');
const cropContext = cropCanvas.getContext('2d');
const cropZoom = document.getElementById('crop-zoom');
let cropImage, cropScale = 1, cropX = 0, cropY = 0, cropMinimum = 1;
let croppedPhoto = null, cropPending = false, cropGeneration = 0, cropDrag = null;
function clampCrop() {
  cropX = Math.min(0, Math.max(800 - cropImage.width * cropScale, cropX));
  cropY = Math.min(0, Math.max(480 - cropImage.height * cropScale, cropY));
}
function drawCrop(guides = true) {
  clampCrop();
  cropContext.fillStyle = 'white'; cropContext.fillRect(0, 0, 800, 480);
  cropContext.drawImage(cropImage, cropX, cropY, cropImage.width * cropScale, cropImage.height * cropScale);
  if (guides && document.getElementById('crop-guides').checked) {
    cropContext.fillStyle = 'rgba(255,255,255,.78)';
    cropContext.fillRect(376, 24, 400, 184); cropContext.fillRect(376, 220, 400, 236);
    cropContext.fillRect(24, 24, 328, 76); cropContext.fillRect(24, 350, 328, 106);
    cropContext.fillStyle = '#172c24'; cropContext.font = '20px sans-serif';
    cropContext.fillText('天气与穿衣建议', 400, 60); cropContext.fillText('待办与每日英语', 400, 258);
    cropContext.fillText('日期', 44, 65); cropContext.fillText('每日名言', 44, 390);
  }
}
function resetCrop() {
  cropMinimum = Math.max(800 / cropImage.width, 480 / cropImage.height);
  cropScale = cropMinimum; cropZoom.value = '1';
  cropX = (800 - cropImage.width * cropScale) / 2;
  cropY = (480 - cropImage.height * cropScale) / 2;
  drawCrop();
}
document.getElementById('background').onchange = async event => {
  const generation = ++cropGeneration;
  const file = event.target.files[0];
  background = ''; croppedPhoto = null; cropPending = !!file;
  document.getElementById('crop-editor').hidden = true;
  if (!file) return;
  if (file.size > 30 * 1024 * 1024) { statusEl.textContent = '请选择 30 MB 以内的照片'; return; }
  try {
    // Browser decoder applies image orientation before the crop is calculated.
    const bitmap = await createImageBitmap(file);
    if (generation !== cropGeneration) { bitmap.close(); return; }
    if (cropImage) cropImage.close();
    cropImage = bitmap;
    document.getElementById('crop-editor').hidden = false;
    resetCrop(); statusEl.textContent = '拖动照片、调整缩放后，点击“确认裁剪”';
  } catch (error) { statusEl.textContent = '无法打开照片，请导出为 JPG、PNG 或 WebP 后重试'; }
};
cropCanvas.addEventListener('pointerdown', event => {
  if (!cropImage) return;
  cropCanvas.setPointerCapture(event.pointerId);
  cropDrag = {x: event.clientX, y: event.clientY, left: cropX, top: cropY};
});
cropCanvas.addEventListener('pointermove', event => {
  if (!cropDrag) return;
  const bounds = cropCanvas.getBoundingClientRect();
  cropX = cropDrag.left + (event.clientX - cropDrag.x) * 800 / bounds.width;
  cropY = cropDrag.top + (event.clientY - cropDrag.y) * 480 / bounds.height;
  cropPending = true; drawCrop();
});
for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
  cropCanvas.addEventListener(type, () => { cropDrag = null; });
}
cropZoom.oninput = () => {
  if (!cropImage) return;
  const oldScale = cropScale;
  cropScale = cropMinimum * Number(cropZoom.value);
  cropX = 400 - (400 - cropX) * cropScale / oldScale;
  cropY = 240 - (240 - cropY) * cropScale / oldScale;
  cropPending = true; drawCrop();
};
document.getElementById('crop-guides').onchange = () => { if (cropImage) drawCrop(); };
document.getElementById('crop-reset').onclick = () => { if (cropImage) { cropPending = true; resetCrop(); } };
document.getElementById('crop-confirm').onclick = () => {
  if (!cropImage) return;
  drawCrop(false); // Guides never enter the uploaded photograph.
  background = cropCanvas.toDataURL('image/jpeg', .95);
  const bytes = Uint8Array.from(atob(background.split(',')[1]), character => character.charCodeAt(0));
  croppedPhoto = new Blob([bytes], {type: 'image/jpeg'});
  cropPending = false; drawCrop();
  statusEl.textContent = '已裁剪为 800 × 480，可预览、加入图库或发送';
  if (document.getElementById('token').value.trim()) run(false);
};
