# scanner.py
import subprocess

def scan_image(image_name: str, output_file: str = "trivy-report.json") -> None:
    command = [
        "trivy", "image", "--format", "json", "-o", output_file, image_name
    ]
    subprocess.run(command, check=True)
