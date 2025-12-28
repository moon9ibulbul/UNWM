import cv2
import numpy as np

def create_sample_images():
    # 1. Template: 50x50 Dark Square (30) on Transparent (Alpha)
    # OpenCV template matching with mask uses separate Mask image.
    # So Template is (50,50) value 30. Mask is (50,50) value 255.
    template = np.full((50, 50), 30, dtype=np.uint8)
    mask = np.full((50, 50), 255, dtype=np.uint8)

    # 2. Dark Background Image: 200x200 Dark (10)
    # Place watermark at (50, 50)
    img_dark = np.full((200, 200), 10, dtype=np.uint8)
    img_dark[50:100, 50:100] = 30 # Place watermark

    # 3. Light Background Image: 200x200 Light (240)
    img_light = np.full((200, 200), 240, dtype=np.uint8)
    img_light[50:100, 50:100] = 30 # Place watermark

    return template, mask, img_dark, img_light

def run_test():
    template, mask, img_dark, img_light = create_sample_images()

    print("--- Test: Dark Watermark (30) on Dark Background (10) ---")
    res_norm = cv2.matchTemplate(img_dark, template, cv2.TM_CCORR_NORMED, mask=mask)
    min_val, max_val, min_loc, max_loc = cv2.minMaxLoc(res_norm)
    print(f"Normal Match Max Score: {max_val:.4f}")

    # Inverted
    img_dark_inv = cv2.bitwise_not(img_dark)
    template_inv = cv2.bitwise_not(template)
    # Mask stays same
    res_inv = cv2.matchTemplate(img_dark_inv, template_inv, cv2.TM_CCORR_NORMED, mask=mask)
    min_val_inv, max_val_inv, min_loc_inv, max_loc_inv = cv2.minMaxLoc(res_inv)
    print(f"Inverted Match Max Score: {max_val_inv:.4f}")

    print("\n--- Test: Dark Watermark (30) on Light Background (240) ---")
    res_norm = cv2.matchTemplate(img_light, template, cv2.TM_CCORR_NORMED, mask=mask)
    min_val, max_val, min_loc, max_loc = cv2.minMaxLoc(res_norm)
    print(f"Normal Match Max Score: {max_val:.4f}")

    # Inverted
    img_light_inv = cv2.bitwise_not(img_light)
    template_inv = cv2.bitwise_not(template)
    res_inv = cv2.matchTemplate(img_light_inv, template_inv, cv2.TM_CCORR_NORMED, mask=mask)
    min_val_inv, max_val_inv, min_loc_inv, max_loc_inv = cv2.minMaxLoc(res_inv)
    print(f"Inverted Match Max Score: {max_val_inv:.4f}")

if __name__ == "__main__":
    run_test()
