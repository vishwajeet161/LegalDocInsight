import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AnalysisService } from './analysis';

describe('Analysis', () => {
  let service: AnalysisService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AnalysisService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('requests document status with the token in a header', () => {
    const http = TestBed.inject(HttpTestingController);
    let receivedStatus = '';
    service.getDocumentStatus('document-1', 'secret-token').subscribe(result => {
      receivedStatus = result.status;
    });

    const request = http.expectOne('http://localhost:8080/api/documents/document-1');
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.get('X-Document-Token')).toBe('secret-token');
    expect(request.request.urlWithParams).not.toContain('secret-token');
    request.flush({ id: 'document-1', status: 'EXTRACTING' });
    expect(receivedStatus).toBe('EXTRACTING');
    http.verify();
  });
});
