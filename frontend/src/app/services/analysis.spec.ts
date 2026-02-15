import { TestBed } from '@angular/core/testing';

import { AnalysisService } from './analysis';

describe('Analysis', () => {
  let service: AnalysisService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AnalysisService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
