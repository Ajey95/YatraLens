"""Generate a clearly synthetic OCR API fixture, not a real scene-photo benchmark."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


def main() -> None:
    output = Path(__file__).resolve().parents[1] / "sample-data" / "synthetic_malayalam_notice.png"
    output.parent.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (1200, 480), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype("C:/Windows/Fonts/Nirmala.ttc", 64)
    small = ImageFont.truetype("C:/Windows/Fonts/Nirmala.ttc", 42)
    draw.text((70, 60), "ആലപ്പുഴ ബോട്ട് ജെട്ടി", font=font, fill="black", language="ml")
    draw.text((70, 190), "സമയം 15:30", font=font, fill="black", language="ml")
    draw.text((70, 350), "SYNTHETIC TEST IMAGE — NOT A REAL NOTICE", font=small, fill="red")
    image.save(output)
    print(output)


if __name__ == "__main__":
    main()

