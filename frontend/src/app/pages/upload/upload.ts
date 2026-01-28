import { Component, signal } from '@angular/core';

@Component({
  selector: 'app-upload',
  imports: [],
  templateUrl: './upload.html',
  styleUrl: './upload.css',
})
export class Upload {
  file = signal<File | null>(null);
  isDragging = signal(false);
  error = signal<string | null>(null);

  onDragOver(event: DragEvent) {
    event.preventDefault();
    this.isDragging.set(true);
  }

  onDragLeave() {
    this.isDragging.set(false);
  }

  onDrop(event: DragEvent) {
    event.preventDefault();
    this.isDragging.set(false);

    const droppedFile = event.dataTransfer?.files?.[0];
    if (droppedFile) {
      this.validateAndSetFile(droppedFile);
    }
  }

  onFileSelect(event: Event) {
    const input = event.target as HTMLInputElement;
    const selectedFile = input.files?.[0];
    if (selectedFile) {
      this.validateAndSetFile(selectedFile);
    }
  }

  private validateAndSetFile(file: File) {
    const allowedTypes = [
      'application/pdf',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    ];

    if (!allowedTypes.includes(file.type)) {
      this.error.set('Only PDF or DOCX files are allowed.');
      this.file.set(null);
      return;
    }

    this.error.set(null);
    this.file.set(file);
  }

  removeFile() {
    this.file.set(null);
  }
}
