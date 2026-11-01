import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Upload } from './upload';
import { AnalysisService } from '../../services/analysis';
import { of } from 'rxjs';
import { vi } from 'vitest';

describe('Upload', () => {
  let component: Upload;
  let fixture: ComponentFixture<Upload>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Upload],
      providers: [{
        provide: AnalysisService,
        useValue: { analyzeFile: () => of({ documentId: 'document-1', accessToken: 'test-private-token' }) }
      }]
    })
    .compileComponents();

    fixture = TestBed.createComponent(Upload);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('does not write the upload access token to the console', () => {
    const consoleLog = vi.spyOn(console, 'log').mockImplementation(() => {});
    try {
      component.file.set(new File(['test'], 'test.pdf', { type: 'application/pdf' }));
      component.analyzeDoc({});
      expect(component.docId).toBe('document-1');
      expect(JSON.stringify(consoleLog.mock.calls)).not.toContain('test-private-token');
    } finally {
      consoleLog.mockRestore();
    }
  });
});
