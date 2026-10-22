import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from app import Item,key

def test_session():
    assert key('a'*64)=='cart:'+'a'*64
    with pytest.raises(HTTPException):key('other-user')
@pytest.mark.parametrize('qty',[0,21,-1,1.5,'2'])
def test_quantity(qty):
    with pytest.raises(ValidationError):Item(quantity=qty)
