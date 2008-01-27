;;; C declaration compiler.

;*           Copyright 1989-1993 Digital Equipment Corporation
;*                         All Rights Reserved
;*
;* Permission to use, copy, and modify this software and its documentation is
;* hereby granted only under the following terms and conditions.  Both the
;* above copyright notice and this permission notice must appear in all copies
;* of the software, derivative works or modified versions, and any portions
;* thereof, and both notices must appear in supporting documentation.
;*
;* Users of this software agree to the terms and conditions set forth herein,
;* and hereby grant back to Digital a non-exclusive, unrestricted, royalty-free
;* right and license under any changes, enhancements or extensions made to the
;* core functions of the software, including but not limited to those affording
;* compatibility with other hardware or software environments, but excluding
;* applications which incorporate this software.  Users further agree to use
;* their best efforts to return to Digital any such changes, enhancements or
;* extensions that they make and inform Digital of noteworthy uses of this
;* software.  Correspondence should be provided to Digital at:
;* 
;*                       Director of Licensing
;*                       Western Research Laboratory
;*                       Digital Equipment Corporation
;*                       250 University Avenue
;*                       Palo Alto, California  94301  
;* 
;* This software may be distributed (but not offered for sale or transferred
;* for compensation) to third parties, provided such third parties agree to
;* abide by the terms and conditions of this notice.  
;* 
;* THE SOFTWARE IS PROVIDED "AS IS" AND DIGITAL EQUIPMENT CORP. DISCLAIMS ALL
;* WARRANTIES WITH REGARD TO THIS SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF
;* MERCHANTABILITY AND FITNESS.   IN NO EVENT SHALL DIGITAL EQUIPMENT
;* CORPORATION BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL
;* DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR
;* PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS
;* ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS
;* SOFTWARE.

;;; This module compiles "extern" forms which define C library procedures.
;;;
;;;	<extern> ::= ( EXTERN <type> <fname> [ <arg> ... ] )
;;;
;;;	<fname>  ::= a Scheme string
;;;
;;;	<arg>	 ::= ( <type> <id> )
;;;		     ( IN <type> <id> )
;;;		     ( OUT <type> <id> )
;;;		     ( IN_OUT <type> <id> )
;;;
;;;	<id>	 ::= a Scheme symbol

(module extern)

;;; The following function syntax checks an extern expression.  It will either
;;; report an error, or return the expression as its value.

(define (INPUT-EXTERN exp)
    (if (and (>= (length exp) 3)
	     (parse-type (cadr exp))
	     (string? (caddr exp)))
	(begin (for-each parse-arg (cdddr exp))
	       exp)
	(error 'input-extern "Illegal EXTERN syntax: ~s" exp)))

;;; Parses the argument list and calls error on an error.

(define (PARSE-ARG exp)
    (if (and (pair? exp)
	     (or (and (= (length exp) 2)
		      (parse-type (car exp))
		      (symbol? (cadr exp)))
		 (and (= (length exp) 3)
		      (memq (car exp) '(in out in_out))
		      (parse-type (cadr exp))
		      (symbol? (caddr exp)))))
	#t
	(error 'PARSE-ARG "Illegal ARGUMENT syntax: ~s" exp)))

;;; Code is generated by the following function.

(define (EMIT-EXTERNS externs extern-file-root type-file-root)
    (let ((module (uis extern-file-root)))
	 (with-output-to-file
	     (string-append extern-file-root ".sc")
	     (lambda ()
		     (write `(module ,module))
		     (newline)
		     (write `(include ,(string-append type-file-root ".sch")))
		     (newline)
		     (for-each (lambda (x) (emit-extern x 'define)) externs)))
	 (with-output-to-file
	     (string-append extern-file-root ".sch")
	     (lambda ()
		     (for-each (lambda (x) (emit-define-external x module))
			 externs)))))

;;; The definition for the interface procedure for an extern is created by
;;; the following procedure.

(define (EMIT-EXTERN extern defform)
    (let ((xname (uis (caddr extern) "*"))
	  (rettype (cadr extern))
	  (args (cdddr extern)))
	 
	 (define (EMIT-CALL)
		 `(,xname ,@(map (lambda (x) (car (last-pair x))) args)))
	 
	 (define (FORMALS args)
		 (if args
		     (if (eq? (caar args) 'out)
			 (formals (cdr args))
			 (cons (car (last-pair (car args)))
			       (formals (cdr args))))
		     '()))
	 
	 (pp `(define-c-external
		  (,xname ,@(map simple-type args))
		  ,(simple-type (list rettype 'returned))
		  ,(caddr extern)))
	 (newline)
	 (pp `(,defform (,(uis (caddr extern)) ,@(formals args))
	       (let* (,@(map arg-in args)
		      (return-value
			  ,(cond ((eq? rettype 'void)
				  `(begin ,(emit-call) #f))
				 ((eq? rettype 'string)
				  `(c-string->string ,(emit-call)))
				 ((isa-pointer? rettype)
				  `(cons ',(base-type rettype)
					 ,(emit-call)))
				 (else (emit-call)))))
		     ,(let ((out (args-out args)))
			   (if out
			       (if (eq? rettype 'void)
				   (if (= (length out) 1)
				       (car out)
				       `(list ,@out))
				   `(list return-value ,@out))
			       'return-value)))))
	 (newline)))

;;; Called to do input conversion for arguments.  Return an expression
;;; of th form (<var> <value>).

(define (ARG-IN arg)
    (let* ((flag (if (memq (car arg) '(in out in_out))
		     (car arg)
		     #f))
	   (type (if flag (cadr arg) (car arg)))
	   (var  (if flag (caddr arg) (cadr arg))))
	  (case flag
		((in in_out) (cond ((eq? (base-type type) 'int)
				    `(,var (let ((_buf (make-string
							   ,(size-of 'int))))
						(c-int-set! _buf 0 ,var)
						_buf)))
				   (else `(,var (in->c ,var)))))
		((out) `(,var (make-string ,(if (eq? type 'string)
						(size-of 'pointer)
						(aligned-size-of type)))))
		(else (cond ((eq? type 'string)
			     `(,var (if (string? ,var)
					,var
					(error 'chk-string
					       "Argument is incorrect type: ~s"
					       ,var))))
			    ((isa-pointer? type)
			     `(,var (,(uis "CHK-" (base-type type)) ,var)))
			    (else  `(,var ,var)))))))

;;; Return a list of the expressions required to do output conversion after
;;; an external call.
	 
(define (ARGS-OUT args)
    
    (define (ARG-OUT arg)
	    (let* ((flag (if (memq (car arg) '(in out in_out))
			     (car arg)
			     #f))
		   (type (if flag (cadr arg) (car arg)))
		   (var  (if flag (caddr arg) (cadr arg))))
		  (case flag
			((in) #f)
			((in_out out)
			 (cond ((eq? type 'string)
				`(c-string->string (c-unsigned-ref ,var 0)))
			       ((isa-pointer? type)
				`(cons ',(base-type type)
				       (c-unsigned-ref ,var 0)))
			       ((or (isa-union? type) (isa-struct? type)
				    (isa-array? type))
				`(cons ',(pointed-to-by type) ,var))
			       (else `(,(getprop (base-type type) 'to-get)
				       ,var 0))))
			(else #f))))

    (if args
	(let ((out (arg-out (car args))))
	     (if out
		 (cons out (args-out (cdr args)))
		 (args-out (cdr args))))
	'()))

;;; Converts the type of a procedure argument to a simple C-type.

(define (SIMPLE-TYPE type)
    (cond ((memq (car type) '(in out in_out string)) 'pointer)
	  ((eq? (car type) 'void) 'void)
	  ((isa-pointer? (car type)) 'pointer)
	  ((isa-procp? (car type)) 'pointer)
	  (else (base-type (car type)))))

;;; The STUBS file is written by the following function.

(define (EMIT-STUBS externs stubs-file-root)
    (with-output-to-file
	(string-append stubs-file-root ".sc")
	(lambda ()
		(write `(module ,(uis stubs-file-root)))
		(newline)
		(for-each emit-stub externs))))

;;; The external definition for a procedure is written by the following
;;; function.

(define (EMIT-DEFINE-EXTERNAL extern module)
    (let ((formals (let loop ((args (cdddr extern))
			      (formals '(a b c d e f g h i j k l m
					   n o p q r s t u v w x y z)))
			(cond ((null? args) '())
			      ((eq? (caar args) 'out)
			       (loop (cdr args) (cdr formals)))
			      (else (cons (car formals)
					  (loop (cdr args) (cdr formals))))))))
	 
	 (pp `(define-external (,(uis (caddr extern)) ,@formals) ,module))
	 (newline)))

;;; The definition for a stub procedure is written by the following function.

(define (EMIT-STUB extern)
    (let* ((c-name (uis (caddr extern) "**"))
	   (stub-name (uis (caddr extern) "*"))
	   (rettype (cadr extern))
	   (args (cdddr extern))
	   (formals (let loop ((args args)
			       (formals '(a b c d e f g h i j k l m
					    n o p q r s t u v w x y z)))
			 (if (not (null? args))
			     (cons (car formals)
				   (loop (cdr args) (cdr formals)))
			     '()))))
	  
	  (pp `(define-c-external
		   (,c-name ,@(map simple-type args))
		   ,(simple-type (list rettype 'returned))
		   ,(caddr extern)))
	  (newline)
	  (pp `(define (,stub-name ,@formals)
		       (,c-name ,@formals)
		       ,@(if (eq? rettype 'void) '(#f) '())))
	  (newline)))

					 
					 